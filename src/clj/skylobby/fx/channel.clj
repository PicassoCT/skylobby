(ns skylobby.fx.channel
  (:require
    [cljfx.api :as fx]
    [clojure.string :as string]
    [skylobby.fx :refer [monospace-font-family]]
    [skylobby.fx.ext :refer [ext-recreate-on-key-changed ext-scroll-on-create]]
    [skylobby.fx.rich-text :as fx.rich-text]
    [skylobby.fx.virtualized-scroll-pane :as fx.virtualized-scroll-pane]
    [spring-lobby.util :as u]
    [taoensso.tufte :as tufte])
  (:import
    (org.fxmisc.richtext.model ReadOnlyStyledDocumentBuilder SegmentOps StyledSegment)))


(set! *warn-on-reflection* true)


(def default-font-size 18)

(defn font-size-or-default [font-size]
  (int (or (when (number? font-size) font-size)
           default-font-size)))

(def irc-colors
  {"00" "rgb(255,255,255)"
   "01" "rgb(255,255,255)" ; use white for black since dark theme "rgb(0,0,0)"
   "02" "rgb(0,0,127)"
   "03" "rgb(0,147,0)"
   "04" "rgb(255,0,0)"
   "05" "rgb(127,0,0)"
   "06" "rgb(156,0,156)"
   "07" "rgb(252,127,0)"
   "08" "rgb(255,255,0)"
   "09" "rgb(0,252,0)"
   "10" "rgb(0,147,147)"
   "11" "rgb(0,255,255)"
   "12" "rgb(0,0,252)"
   "13" "rgb(255,0,255)"
   "14" "rgb(127,127,127)"
   "15" "rgb(210,210,210)"})

(defn text-style [font-size]
  {:-fx-font-family monospace-font-family
   :-fx-font-size (font-size-or-default font-size)})


(defn segment
  [text style]
  (StyledSegment. text style))

(defn channel-document
  ([messages]
   (channel-document messages nil))
  ([messages {:keys [highlight]}]
   (let [highlight (->> highlight
                        (filter some?)
                        (map string/trim)
                        (remove string/blank?))
         builder (ReadOnlyStyledDocumentBuilder. (SegmentOps/styledTextOps) "")]
     (doseq [message messages]
       (let [{:keys [message-type text timestamp username]} message]
         (.addParagraph builder
           ^java.util.List
           (vec
             (concat
               [
                (segment
                  (str "[" (u/format-hours timestamp) "] ")
                  ["text" "skylobby-chat-time"])
                (segment
                  (str
                    (case message-type
                      :ex (str "* " username " " text)
                      :join (str username " has joined")
                      :leave (str username " has left")
                      ; else
                      (str username ": ")))
                  ["text" (str "skylobby-chat-username" (when message-type (str "-" (name message-type))))])]
               (when-not message-type
                 (map
                   (fn [[_all _ _irc-color-code text-segment]]
                     (segment
                       (str text-segment)
                       ["text"
                        (if (and (seq highlight)
                                 (some (fn [substr]
                                         (and text-segment substr
                                              (string/includes? (string/lower-case text-segment)
                                                                (string/lower-case substr))))
                                       highlight))
                          "skylobby-chat-message-highlight"
                          "skylobby-chat-message")]))
                   (re-seq #"([\u0003](\d\d))?([^\u0003]+)" text)))))
           ^java.util.List
           [])))
     (when (seq messages)
       (.build builder)))))

(defn- channel-view-history-impl
  [{:fx/keys [context]
    :keys [channel-name server-key]}]
  (let [messages (fx/sub-val context get-in [:by-server server-key :channels channel-name :messages])
        username (fx/sub-val context get-in [:by-server server-key :username])
        chat-auto-scroll (fx/sub-val context :chat-auto-scroll)
        chat-font-size (fx/sub-val context :chat-font-size)
        chat-highlight-username (fx/sub-val context :chat-highlight-username)
        chat-highlight-words (fx/sub-val context :chat-highlight-words)
        hide-joinas-spec (fx/sub-val context :hide-joinas-spec)
        hide-spads-messages (fx/sub-val context :hide-spads-messages)
        hide-vote-messages (fx/sub-val context :hide-vote-messages)
        ignore-users (fx/sub-val context :ignore-users)
        ignore-users-set (->> (get ignore-users server-key)
                              (filter second)
                              (map first)
                              set)
        hide-spads-set (->> hide-spads-messages
                            (filter second)
                            (map first)
                            set)]
    {:fx/type ext-recreate-on-key-changed
     :key {:ignore ignore-users-set :joinas-spec hide-joinas-spec :spads hide-spads-set :vote hide-vote-messages}
     :desc
     {:fx/type ext-scroll-on-create
      :desc
      {:fx/type fx.virtualized-scroll-pane/lifecycle
       :event-filter {:event/type :spring-lobby/filter-channel-scroll}
       :content
       {:fx/type fx.rich-text/lifecycle-fast
        :editable false
        :style (text-style chat-font-size)
        :wrap-text true
        :document
        [
         (->> messages
              (remove (comp ignore-users-set :username))
              (remove (comp hide-spads-set :spads-message-type :spads))
              (remove (if hide-vote-messages (comp :vote :vote) (constantly false)))
              (remove (if hide-joinas-spec (comp #{"joinas spec"} :command :vote) (constantly false)))
              (remove
                (fn [{:keys [message-type text]}]
                  (and (= :ex message-type)
                       text
                       (string/starts-with? text "* BarManager|"))))
              reverse)
         (fn [lines]
           (channel-document
             lines
             {:highlight
              (concat
                (when chat-highlight-words
                  (string/split chat-highlight-words #"[\s,]+"))
                (when chat-highlight-username
                  [username]))}))
         chat-auto-scroll]}}}}))

(defn channel-view-history
  [state]
  (tufte/profile {:dynamic? true
                  :id :skylobby/ui}
    (tufte/p :channel-view-history
      (channel-view-history-impl state))))

(defn- channel-view-input
  [{:fx/keys [context]
    :keys [channel-name server-key]}]
  (let [client-data (fx/sub-val context get-in [:by-server server-key :client-data])
        message-draft (fx/sub-val context get-in [:by-server server-key :message-drafts channel-name])]
    {:fx/type :h-box
     :children
     [{:fx/type :button
       :text "Send"
       :on-action {:event/type :spring-lobby/send-message
                   :channel-name channel-name
                   :client-data client-data
                   :message message-draft
                   :server-key server-key}}
      {:fx/type :text-field
       :id "channel-text-field"
       :h-box/hgrow :always
       :text (str message-draft)
       :on-text-changed {:event/type :spring-lobby/assoc-in
                         :path [:by-server server-key :message-drafts channel-name]}
       :on-action {:event/type :spring-lobby/send-message
                   :channel-name channel-name
                   :client-data client-data
                   :message message-draft
                   :server-key server-key}
       :on-key-pressed {:event/type :spring-lobby/on-channel-key-pressed
                        :channel-name channel-name
                        :server-key server-key}}]}))

(defn- channel-view-users
  [{:fx/keys [context]
    :keys [channel-name server-key]}]
  (let [users (fx/sub-val context get-in [:by-server server-key :channels channel-name :users])]
    {:fx/type :table-view
     :column-resize-policy :constrained
     :items (->> users
                 keys
                 (sort String/CASE_INSENSITIVE_ORDER)
                 vec)
     :row-factory
     {:fx/cell-type :table-row
      :describe (fn [i]
                  {
                   :context-menu
                   {:fx/type :context-menu
                    :items
                    [
                     {:fx/type :menu-item
                      :text "Message"
                      :on-action {:event/type :spring-lobby/join-direct-message
                                  :username i}}]}})}
     :columns
     [{:fx/type :table-column
       :text "Username"
       :cell-value-factory identity
       :cell-factory
       {:fx/cell-type :table-cell
        :describe
        (fn [i]
          {:text (-> i str)
           :style-class ["text" "skylobby-chat-user-list"]})}}]}))


(defn channel-view-impl
  [{:keys [channel-name hide-users server-key]}]
  {:fx/type :h-box
   :children
   (concat
     [{:fx/type :v-box
       :h-box/hgrow :always
       :style {:-fx-font-size 16}
       :children
       [{:fx/type channel-view-history
         :v-box/vgrow :always
         :channel-name channel-name
         :server-key server-key}
        {:fx/type channel-view-input
         :channel-name channel-name
         :server-key server-key}]}]
     (when (and (not hide-users)
                channel-name
                (not (string/starts-with? channel-name "@")))
       [{:fx/type channel-view-users
         :channel-name channel-name
         :server-key server-key}]))})


(defn channel-view
  [state]
  (tufte/profile {:dynamic? true
                  :id :skylobby/ui}
    (tufte/p :channel-view
      (channel-view-impl state))))
