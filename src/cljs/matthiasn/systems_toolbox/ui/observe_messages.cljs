(ns matthiasn.systems-toolbox.ui.observe-messages
  (:require-macros [cljs.core.async.macros :refer [go-loop]])
  (:require [reagent.core :as r :refer [atom]]
            [cljs.core.match :refer-macros [match]]
            [matthiasn.systems-toolbox.component :as comp]
            [matthiasn.systems-toolbox.helpers :refer [by-id now]]))

(defn messages-view
  "Render application state snapshot."
  [app]
  (let [state @app
        show (:show state)]
    [:div
     [:a {:style {:float :right :margin "10px" :color (if show "steelblue" "#AAA")}
          :on-click #(swap! app update-in [:show] not)} "messages"]
     (when show
       [:div
        (for [[timestamp from msg] (reverse (take-last 10 (:messages state)))]
          ^{:key (str "msg-" timestamp)}
          [:div
           [:h3 (str "Message from " from)]
           [:h6 timestamp]
           [:pre [:code (str msg)]]])])]))

(defn mk-state
  "Return clean initial component state atom."
  [dom-id]
  (fn [put-fn]
    (let [app (atom {:messages [] :show false})]
      (r/render-component [messages-view app] (by-id dom-id))
      app)))

(defn in-handler
  "Handle incoming messages: process / add to application state."
  [app put-fn msg]
  (swap! app assoc :messages (conj (:messages @app) [(now) (:from (meta msg)) msg])))

(defn component
  [cmp-id dom-id]
  (comp/make-component cmp-id (mk-state dom-id) in-handler nil))
