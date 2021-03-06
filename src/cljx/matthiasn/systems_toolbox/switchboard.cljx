(ns matthiasn.systems-toolbox.switchboard
  #+clj (:gen-class)
  (:require
    #+clj [clojure.core.match :refer [match]]
    #+cljs [cljs.core.match :refer-macros [match]]
    #+clj [clojure.core.async :refer [put! sub tap]]
    #+cljs [cljs.core.async :refer [put! sub tap]]
    [matthiasn.systems-toolbox.component :as comp]
    [matthiasn.systems-toolbox.log :as l]))

(defn wire-comp
  "Wire existing and already instantiated component."
  [app put-fn cmps]
  (doseq [cmp (flatten [cmps])]
    (let [cmp-id (:cmp-id cmp)]
      (put-fn [:log/switchboard-wire cmp-id])
      (swap! app assoc-in [:components cmp-id] cmp))))

(defn subscribe
  "Subscribe component to a specified publisher."
  [app put-fn [from-cmp from-pub] msg-type [to-cmp to-chan]]
  (let [pub-comp (from-cmp (:components @app))
        sub-comp (to-cmp (:components @app))]
    (sub (from-pub pub-comp) msg-type (to-chan sub-comp))
    (put-fn [:log/switchboard-sub (str from-cmp " -[" msg-type "]-> " to-cmp)])
    (swap! app update-in [:subs] conj [from-cmp to-cmp])))

(defn subscribe-comp-state
  "Subscribe component to a specified publisher."
  [app put-fn [from to]]
  (doseq [t (flatten [to])]
    (subscribe app put-fn [from :state-pub] :app-state [t :sliding-in-chan])))

(defn subscribe-comp
  "Subscribe component to a specified publisher."
  [app put-fn sources destination]
  (doseq [[from msg-type] sources]
    (subscribe app put-fn [from :out-pub] msg-type [destination :in-chan])))

(defn subscribe-comp-bidirectional
  "Subscribes cmp1 to all message types of cmp2 and, at the same time,
  subscribes cmp2 to all message types of cmp1."
  [app put-fn cmp1 cmp2 msg-types]
  (letfn [(sub-msg-type [from to m] (subscribe app put-fn [from :out-pub] m [to :in-chan]))]
    (doseq [msg-type msg-types]
      (sub-msg-type cmp1 cmp2 msg-type)
      (sub-msg-type cmp2 cmp1 msg-type))))

(defn tap-components
  "Tap into a mult."
  [app put-fn [from-cmps to]]
  (doseq [from (flatten [from-cmps])]
    (let [mult-comp (from (:components @app))
          tap-comp (to  (:components @app))
          err-put #(put-fn [:log/switchboard-tap (str "Could not create tap: " from " -> " to " - " %)])]
      (try
        (do
          (tap (:out-mult mult-comp) (:in-chan tap-comp))
          (put-fn [:log/switchboard-tap (str from " -> " to)])
          (swap! app update-in [:taps] conj [from to]))
        #+clj (catch Exception e (err-put (.getMessage e)))
        #+cljs (catch js/Object e (err-put e))))))

(defn tap-components-bidirectional
  "Same as tap-components, but is symmetric - taps cmp1 into cmp2
  and, at the same time taps, cmp2 into cmp1."
  [app put-fn cmp1 cmp2]
  (tap-components app put-fn [[cmp1] cmp2])
  (tap-components app put-fn [[cmp2] cmp1]))

(defn make-log-comp
  "Creates a log component."
  [app put-fn]
  (let [log-comp (l/component)]
    (swap! app assoc-in [:components :log-cmp] log-comp)
    (put-fn [:log/switchboard-init :log-cmp])
    log-comp))

(defn- self-register
  "Registers switchboard itself as another component that can be wired. Useful
  for communication with the outside world / within hierarchies where a subsystem
  has its own switchboard."
  [app put-fn self]
  (swap! app assoc-in [:components :switchboard] self))

(defn make-state
  "Return clean initial component state atom."
  [put-fn]
  (let [app (atom {:components {} :subs #{} :taps #{}})]
    app))

(defn send-to
  "Send message to the specified component."
  [app [dest-id msg]]
  (let [dest-comp (dest-id (:components @app))]
    (put! (:in-chan dest-comp) msg)))

(defn in-handler
  "Handle incoming messages: process / add to application state."
  [app put-fn msg]
  (match msg
         [:cmd/self-register     self] (self-register app put-fn self)
         [:cmd/wire-comp          cmp] (wire-comp app put-fn cmp)
         [:cmd/make-log-comp         ] (make-log-comp app put-fn)
         [:cmd/send-to            env] (send-to app env)
         [:cmd/sub-comp-state from-to] (subscribe-comp-state app put-fn from-to)
         [:cmd/sub-comp  sources dest] (subscribe-comp app put-fn sources dest)
         [:cmd/sub-comp-2 cmp1 cmp2 m] (subscribe-comp-bidirectional app put-fn cmp1 cmp2 m)
         [:cmd/tap-comp       from-to] (tap-components app put-fn from-to)
         [:cmd/tap-comp-2   cmp1 cmp2] (tap-components-bidirectional app put-fn cmp1 cmp2)
         :else (prn "unknown msg in switchboard-in-loop" msg)))

(defn component
  "Creates a switchboard component that wires individual components together into
  a communicating system."
  []
  (println "Switchboard starting.")
  (let [switchboard (comp/make-component :switchboard make-state in-handler nil)
        sw-in-chan (:in-chan switchboard)]
    (put! sw-in-chan [:cmd/self-register switchboard])
    (put! sw-in-chan [:cmd/make-log-comp])
    (put! sw-in-chan [:cmd/tap-comp [:switchboard :log-cmp]])
    switchboard))

(defn send-cmd
  "Send message to the specified switchboard component."
  [switchboard cmd]
  (put! (:in-chan switchboard) cmd))

(defn send-mult-cmd
  "Send messages to the specified switchboard component."
  [switchboard cmds]
  (doseq [cmd cmds] (put! (:in-chan switchboard) cmd)))
