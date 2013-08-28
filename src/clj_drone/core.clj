(ns clj-drone.core
  (:import (java.net DatagramPacket DatagramSocket InetAddress))
  (:require  [ clj-logging-config.log4j :as log-config]
             [ clojure.tools.logging :as log]
             [clj-drone.at :refer :all]
             [clj-drone.navdata :refer :all]
             [clj-drone.goals :refer :all]))


(def default-drone-ip "192.168.1.1")
(def default-at-port 5556)
(def navdata-port 5554)
(def navdata-socket (DatagramSocket. ))
(def nav-agent (agent {}))
(def drones (atom {}))

(def socket-timeout (atom 10000))
(declare mdrone)
(declare drone-init-navdata)


(defn init-logger []
  (log-config/set-logger! :level :debug
                          :out "logs/drone.log"))
(init-logger)

(defn drone-initialize
  ([] (drone-initialize :default default-drone-ip default-at-port))
  ([name ip at-port]
     (swap! drones assoc name {:host (InetAddress/getByName ip)
                               :at-port at-port
                               :counter (atom 0)
                               :at-socket (DatagramSocket. )
                               :nav-data (atom {})})
     (mdrone name :flat-trim)))

(defn drone-ip [drone-name]
   (.getHostName (:host (drone-name @drones))))

(defn send-command [name data]
  (let [host (:host (name @drones))
        at-port (:at-port (name @drones))
        at-socket (:at-socket (name @drones))]
   (.send at-socket
          (new DatagramPacket (.getBytes data) (.length data) host at-port))))

(defn mdrone [name command-key & [w x y z]]
  (let [counter (:counter (name @drones))
        seq-num (swap! counter inc)
        data (build-command command-key seq-num w x y z)]
    (send-command name data)))

(defn drone [command-key & [w x y z]]
  (mdrone :default command-key w x y z))

(defn mdrone-do-for [name seconds command-key & [w x y z]]
  (when (> seconds 0)
    (mdrone name command-key w x y z)
    (Thread/sleep 30)
    (recur name (- seconds 0.03) command-key [w x y z])))

(defn drone-do-for [seconds command-key & [w x y z]]
  (mdrone-do-for :default seconds command-key w x y z))

(defn find-drone [ip]
  (select-keys @drones (for [[k v] @drones :when (= ip (.getHostAddress (:host v)))] k)))

(defn drone-stop-navdata []
  (reset! stop-navstream true))

(defn get-nav-data [name]
  (:nav-data (name @drones)))

(defn communication-check [name]
  (when (= :problem @(get-nav-data name) :com-watchdog)
    (log/info "Watchdog Reset")
    (drone :reset-watchdog)))

(defn stream-navdata [_ socket packet]
  (do
    (receive-navdata socket packet)
    (println (str "data from" (.getHostAddress (.getAddress packet)) ))
    (let [ipfrom (.getHostAddress (.getAddress packet))
          drone (find-drone ipfrom)
          from-name (first (keys drone))]
      (parse-navdata (get-navdata-bytes packet) (get-nav-data from-name))
      (log/info (str "(" from-name ") " "navdata: "(log-flight-data (get-nav-data from-name))))
      (communication-check from-name)
      (eval-current-goals @(get-nav-data from-name))
      (log/info (log-goal-info)))
    (if @stop-navstream
      (log/info "navstream-ended")
      (recur nil socket packet))))

(defn start-streaming-navdata [name navdata-socket host port nav-agent]
  (let [receive-data (byte-array 2048)
        nav-datagram-receive-packet (new-datagram-packet receive-data host port)]
    (do
      (log/info "Starting navdata stream")
      (swap! (get-nav-data name) {})
      (.setSoTimeout navdata-socket @socket-timeout)
      (send nav-agent stream-navdata navdata-socket nav-datagram-receive-packet)
      (log/info "Creating navdata stream" ))))


(defn init-streaming-navdata [navdata-socket host port]
  (let [send-data (byte-array (map byte [1 0 0 0]))
        nav-datagram-send-packet (new-datagram-packet send-data host port)]
    (do
      (reset-navstream)
      (.setSoTimeout navdata-socket @socket-timeout)
      (send-navdata navdata-socket nav-datagram-send-packet))))

(declare mdrone-init-navdata)

(defn navdata-error-handler [name]
  (fn [ag ex]
    (do
      (println "evil error occured: " ex " and we still have value " @ag)
      (when (= (.getClass ex) java.net.SocketTimeoutException)
        (println "Reststarting nav stream")
        (def navdata-socket (DatagramSocket. ))
        (println "redef navdata-socket")
        (.setSoTimeout navdata-socket @socket-timeout)
        (println "reset socket timout")
        (def nav-agent (agent {}))
        (println (str "agent now is " nav-agent))
        (when-not  @stop-navstream
          (mdrone-init-navdata name))))))

(defn mdrone-init-navdata [name]
  (let [host (:host (name @drones))
        nav-data (:nav-data (name @drones))]
    (do
      (init-logger)
      (log/info "Initializing navdata")
      (log/info nav-agent)
      (reset! nav-data {})
      (set-error-handler! nav-agent (navdata-error-handler name))
      (init-streaming-navdata navdata-socket host navdata-port)
      (drone :init-navdata)
      (drone :control-ack)
      (init-streaming-navdata navdata-socket host navdata-port)
      )))

(defn start-stream [name]
  (let [host (:host (name @drones))]
    (start-streaming-navdata name navdata-socket host navdata-port nav-agent)))

(defn drone-init-navdata []
  (mdrone-init-navdata :default)
  (start-stream :default))


