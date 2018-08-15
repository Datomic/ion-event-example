;; Copyright (c) Cognitect, Inc.
;; All rights reserved.
;;
;; Licensed under the Apache License, Version 2.0 (the "License");
;; you may not use this file except in compliance with the License.
;; You may obtain a copy of the License at
;;
;;      http://www.apache.org/licenses/LICENSE-2.0
;;
;; Unless required by applicable law or agreed to in writing, software
;; distributed under the License is distributed on an "AS-IS" BASIS,
;; WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
;; See the License for the specific language governing permissions and
;; limitations under the License.

(require
 '[clojure.core.async :as a]
 '[clojure.data.json :as json]
 '[clojure.java.io :as io]
 '[clojure.pprint :as pp]
 '[datomic.ion :as ion]
 '[datomic.ion.cast :as cast]
 '[datomic.ion.event-example :as ev]
 '[datomic.client.api :as d])

(cast/initialize-redirect :stdout)

@(def channel (ev/get-param "channel"))
(def conn (ev/get-conn))
(def db (d/db conn))

;; test writing to slack channel
(ev/post-slack-message channel "hi")

;; test handling a CodeDeploy event
(def cd-failure (slurp "fixtures/events/cd/failure.json"))
(ev/event-handler {:input cd-failure})

;; test the query
(ev/recent-deploys db (ev/hours-ago 24) 10)

;; test the code formatting
(ev/tableize-deploys *1)

;; test handling a malformed slack event
(defn sstream [^String s] (java.io.ByteArrayInputStream. (.getBytes s)))
(ev/slack-event-handler* {:body (sstream "{}")})

;; test handling a verified slack-event
(let [json (format "{\"token\": \"%s\"}" (ev/get-param "verification-token"))]
  (ev/slack-event-handler* {:body (sstream json)}))
