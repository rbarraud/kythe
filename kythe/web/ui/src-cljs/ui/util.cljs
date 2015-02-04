;; Copyright 2014 Google Inc. All rights reserved.
;;
;; Licensed under the Apache License, Version 2.0 (the "License");
;; you may not use this file except in compliance with the License.
;; You may obtain a copy of the License at
;;
;;   http://www.apache.org/licenses/LICENSE-2.0
;;
;; Unless required by applicable law or agreed to in writing, software
;; distributed under the License is distributed on an "AS IS" BASIS,
;; WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
;; See the License for the specific language governing permissions and
;; limitations under the License.
(ns ui.util
  (:require-macros [cljs.core.async.macros :refer [go-loop]])
  (:require [cljs.core.async :refer [<!]])
  (:import [goog Uri]))

(defn basename
  "Returns the basename of the given path"
  [path]
  (cond
   (empty? path) path
   (= (get path (- (count path) 1)) "/") (basename (subs path 0 (- (count path) 1)))
   :else (subs path (+ 1 (.lastIndexOf path "/")))))

(defn path-display
  ([{:keys [corpus root path]}]
     (path-display corpus root path))
  ([corpus root path]
     (str corpus ":" root ":" path)))

(defn handle-ch
  ([ch state handler]
     (go-loop [state state]
       (recur (handler (<! ch) state))))
  ([ch handler]
     (go-loop []
       (handler (<! ch))
       (recur))))

(defn- decode-ticket-query [query]
  (let [params (into {}
                 (for [arg (.split query "?")]
                   (let [[key val] (.split arg "=" 2)]
                     [(keyword key) val])))]
    {:language (:lang params)
     :path (:path params)
     :root (:root params)}))

(defn ticket->vname [ticket]
  (let [uri (Uri. ticket)]
    (when-not (= (.getScheme uri))
      (.log js/console (str "WARNING: invalid ticket '" ticket "'")))
    (assoc (decode-ticket-query (.getDecodedQuery uri))
      :corpus (.getDomain uri)
      :signature (.getFragment uri))))