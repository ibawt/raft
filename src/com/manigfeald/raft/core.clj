(ns com.manigfeald.raft.core
  "clojure.core contains lots of functions used in most(all?) clojure
namespaces, com.manigfeald.raft.core contains functions used in
most(all?) com.manigfeald.raft* namespaces"
  (:require [com.manigfeald.raft.log :as log])
  (:import (clojure.lang PersistentQueue)))

(defprotocol RaftOperations
  "The value that you want raft to maintain implements this protocol"
  (apply-operation [value operation]
    "apply-operation returns a tuple of
    [logical-operation-return-value possibly-updated-value]"))

(defrecord MapValue []
  RaftOperations
  (apply-operation [value operation]
    (case (:op operation)
      :read [(get value (:key operation)) value]
      :write [nil (assoc value
                    (:key operation) (:value operation))]
      :write-if (if (contains? value (:key operation))
                  [false value]
                  [true (assoc value
                          (:key operation) (:value operation))])
      :delete [nil (dissoc value (:key operation))]
      (assert nil operation))))

(alter-meta! #'map->MapValue assoc :no-doc true)

(declare log-entry-of
         insert-entries)

(defn set-return-value
  "set the return value of an operation in the log"
  [raft-state index value]
  (let [entry (log-entry-of raft-state index)]
    (insert-entries raft-state [(assoc entry :return value)])))

;; TODO: move add and remove node in to its own code
(defn advance-applied-to-commit
  "given a RaftState, ensure all commited operations have been applied
  to the value"
  [raft-state]
  (if (> (:commit-index raft-state)
         (:last-applied raft-state))
    (let [new-last (inc (:last-applied raft-state))
          op (log-entry-of raft-state new-last)]
      (assert op "op is in the log")
      (case (:operation-type op)
        :add-node (recur (-> raft-state
                             (assoc :last-applied new-last)
                             (update-in [:node-set] conj (:node op))))
        :remove-node (recur (-> raft-state
                                (assoc :last-applied new-last)
                                (update-in [:node-set] disj (:node op))))
        (let [[return new-value] (apply-operation (:value raft-state)
                                                  (:payload op))
              new-state (set-return-value raft-state (:index op) return)]
          (recur (assoc new-state
                   :value new-value
                   :last-applied new-last)))))
    raft-state))

(defn consume-message
  "remove a message from the state"
  [state]
  (assoc-in state [:io :message] nil))

(defn publish
  "add messages to the out-queue in the state"
  [state messages]
  {:pre [(not (map? messages))
         (every? :from messages)]}
  (update-in state [:io :out-queue] into
             (for [message messages
                   :when (not= (:target message) (:id state))]
               message)))

(defn log-contains?
  "does the log in this raft-state contain an entry with the given
  term and index"
  [raft-state log-term log-index]
  (or (and (zero? log-term)
           (zero? log-index))
      (log/log-contains? (:log raft-state) log-term log-index)))

(defn last-log-index [raft-state]
  (biginteger (log/last-log-index (:log raft-state))))

(defn last-log-term [raft-state]
  {:post [(number? %)
          (not (neg? %))]}
  (biginteger (log/last-log-term (:log raft-state))))

(defn broadcast [node-set msg]
  (for [node node-set]
    (assoc msg :target node)))

(defn enough-votes? [total votes]
  (>= votes (inc (Math/floor (/ total 2.0)))))

(defn possible-new-commit
  "is there a log index that is greater than the current commit-index
  and a majority of nodes have a copy of it"
  [commit-index raft-state match-index node-set current-term]
  (first (sort (for [[n c] (frequencies
                            (for [[index term] (log/indices-and-terms
                                                (:log raft-state))
                                  [node match-index] match-index
                                  :when (>= match-index index)
                                  :when (= current-term term)
                                  :when (> index commit-index)]
                              index))
                     :when (>= c (inc (Math/floor (/ (count node-set) 2))))]
                 n))))

(def ^:dynamic *log-context*)

(defn log-trace
  "given a state and a log message (as a seq of strings) append the
  message to the log at the trace level"
  [state & message]
  {:pre [(instance? PersistentQueue (:running-log state))]
   :post [(instance? PersistentQueue (:running-log %))]}
  (update-in state [:running-log]
             (fnil conj PersistentQueue/EMPTY)
             {:level :trace
              :context *log-context*
              :message (apply print-str (:id state) message)}))

(defn serial-exists?
  "does an entry with the given serial exist in the log in the
  raft-state?"
  [raft-state serial]
  (boolean (log/entry-with-serial (:log raft-state) serial)))

(defn add-to-log
  "add the given operation to the log in the raft-state with the next
  index, as long as an entry with that serial number doesn't already
  exist in the log"
  [raft-state operation]
  {:pre [(contains? operation :operation-type)
         (contains? operation :payload)
         (contains? operation :term)
         (number? (:term operation))
         (not (neg? (:term operation)))]}
  (if (serial-exists? raft-state (:serial operation))
    raft-state
    (update-in raft-state [:log]
               log/add-to-log
               (biginteger (inc (last-log-index raft-state)))
               operation)))

(defn insert-entries [raft-state entries]
  {:pre [(every? map? entries)]}
  (assoc raft-state
    :log (reduce
          #(log/add-to-log %1 (biginteger (:index %2)) %2)
          (:log raft-state)
          entries)))

(defn rewrite-terms [raft-state target-index new-term]
  (update-in raft-state [:log] log/rewrite-terms-after
             target-index new-term))

(defn log-entry-of [raft-state index]
  (log/log-entry-of (:log raft-state) index))

(defn log-count [raft-state]
  (log/log-count (:log raft-state)))

(defn empty-log
  "create an empty thing that satisfies the RaftLog protocol"
  []
  (com.manigfeald.raft.log.LogChecker. () {}))

(defn reject-append-entries [state leader-id current-term id]
  (-> state
      (consume-message)
      (publish [{:type :append-entries-response
                 :term current-term
                 :success? false
                 :from id
                 :target leader-id}])
      (assoc-in [:timer :next-timeout] (+ (-> state :timer :period)
                                          (-> state :timer :now)))))

(defn accept-append-entries [state leader-id current-term id]
  (-> state
      (consume-message)
      (publish [{:type :append-entries-response
                 :target leader-id
                 :term current-term
                 :success? true
                 :from id
                 :last-log-index (last-log-index (:raft-state state))}])
      (assoc-in [:raft-state :leader-id] leader-id)
      (assoc-in [:timer :next-timeout] (+ (-> state :timer :period)
                                          (-> state :timer :now)))))