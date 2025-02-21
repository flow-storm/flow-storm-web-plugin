(ns flow-storm.plugins.web.ui
  (:require [flow-storm.debugger.ui.plugins :as fs-plugins]
            [flow-storm.debugger.ui.components :as ui]
            [flow-storm.debugger.ui.tasks :as tasks]
            [flow-storm.debugger.ui.utils :as ui-utils]
            [flow-storm.debugger.ui.flows.screen :refer [goto-location]]
            [flow-storm.debugger.runtime-api :as runtime-api :refer [rt-api]]
            [clojure.string :as str])
  (:import [javafx.scene.layout Priority VBox HBox]
           [javafx.scene.control ScrollPane]))

(defn- build-toolbar [flow-cmb  {:keys [on-reload-click]}]
  (let [reload-btn (ui/icon-button
                    :icon-name "mdi-reload"
                    :on-click on-reload-click
                    :tooltip "Reload the messages from flow-id recordings")]
    (ui/v-box :childs [(ui/h-box :childs [(ui/label :text "Flow-id :") flow-cmb reload-btn]
                                 :spacing 5)]
              :spacing 5)))

(defn- on-create [_]
  (try
    (let [threads-tables-box (ui/v-box :childs [] :spacing 20)
          *thread->table-add-messages (atom {})
          *flow-id (atom 0)
          clear-all-tables (fn [] (.clear (.getChildren threads-tables-box)))
          add-thread-table (fn [thread-id]
                             (let [{:keys [add-all table-view-pane table-view]}
                                   (ui/table-view
                                    :columns ["HTTP" "Logic" "Database"]
                                    :cell-factory (fn [_ {:keys [type] :as msg}]
                                                    (ui/label :text
                                                              (case type
                                                                :fn-call (let [{:keys [fn-ns fn-name level]} msg]
                                                                           (format "%s %s/%s"
                                                                                   (apply str (repeat level " "))
                                                                                   fn-ns
                                                                                   fn-name))
                                                                :http-request  (let [{:keys [uri request-method]} (:req msg)]
                                                                                 (format "[%s] %s" request-method uri))
                                                                :http-response (let [{:keys [status body]} (:response msg)]
                                                                                 (format "[%s] %s" status body))
                                                                :sql           (let [{:keys [statement params]} msg]
                                                                                 (format "%s \n %s" statement params))
                                                                "")))
                                    :resize-policy :constrained
                                    :columns-with-percs [0.3 0.3 0.4]
                                    :on-click (fn [mev sel-rows _]
                                                (when (ui-utils/double-click? mev)
                                                  (let [idx (->> sel-rows first (some (fn [{:keys [idx]}] idx)))]
                                                    (goto-location {:flow-id @*flow-id
                                                                    :thread-id thread-id
                                                                    :idx idx}))))
                                    :selection-mode :single)
                                   table-box (ui/v-box :childs [(ui/label :text (format "Thread id: %d" thread-id))
                                                                table-view-pane])]
                               (doto table-view
                                 (.setMinWidth 1000)
                                 (.setMinWidth 1000))

                               (HBox/setHgrow table-view-pane Priority/ALWAYS)
                               (HBox/setHgrow table-view Priority/ALWAYS)
                               (HBox/setHgrow table-box Priority/ALWAYS)

                               (swap! *thread->table-add-messages assoc thread-id add-all)
                               (.addAll (.getChildren threads-tables-box) [table-box])))
          add-messages-to-thread-table (fn [thread-id messages]
                                         (let [add-messages (get @*thread->table-add-messages thread-id)]
                                           (->> messages
                                                (mapv (fn [{:keys [type] :as m}]
                                                        (case type
                                                          :http-request  [m nil nil]
                                                          :http-response [m nil nil]
                                                          :fn-call       [nil m nil]
                                                          :sql           [nil nil m])))
                                                add-messages)
                                           (add-messages messages)))

          flow-cmb (ui/combo-box :items []
                                 :cell-factory (fn [_ flow-id] (ui/label :text (str flow-id)))
                                 :button-factory (fn [_ flow-id] (ui/label :text (str flow-id)))
                                 :on-change (fn [_ flow-id] (when flow-id (reset! *flow-id flow-id))))

          toolbar-pane (build-toolbar flow-cmb
                                      {:on-reload-click
                                       (fn []
                                         (reset! *thread->table-add-messages {})
                                         (clear-all-tables)
                                         (tasks/submit-task runtime-api/call-by-fn-key
                                                            [:plugins.web/extract-data-task
                                                             [@*flow-id]]
                                                            {:on-progress
                                                             (fn [{:keys [batch]}]
                                                               (let [thread-id (-> batch first :thread-id)]
                                                                 (ui-utils/run-later
                                                                   (when-not (contains? @*thread->table-add-messages thread-id)
                                                                     (add-thread-table thread-id))
                                                                   (add-messages-to-thread-table thread-id batch))))}))})
          tables-scroll (ScrollPane. threads-tables-box)]
      (VBox/setVgrow threads-tables-box Priority/ALWAYS)
      (HBox/setHgrow threads-tables-box Priority/ALWAYS)

      {:fx/node (ui/border-pane
                 :top toolbar-pane
                 :center tables-scroll)
       :flow-cmb flow-cmb
       :selected-flow-id-ref *flow-id})
    (catch Exception e
      (.printStackTrace e)
      (ui/label :text (.getMessage e)))))

(defn- on-focus [{:keys [flow-cmb selected-flow-id-ref]}]
  (let [flow-ids (into #{} (map first (runtime-api/all-flows-threads rt-api)))]
    (ui-utils/combo-box-set-items flow-cmb flow-ids)
    (ui-utils/combo-box-set-selected flow-cmb @selected-flow-id-ref)))

(fs-plugins/register-plugin
 :async-flow
 {:label "Web"
  :dark-css-resource  "flow-storm-web-plugin/dark.css"
  :light-css-resource "flow-storm-web-plugin/light.css"
  :on-focus on-focus
  :on-create on-create})
