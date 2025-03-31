(ns dk.cst.prayer.web.frontend.html
  "Frontend HTML-generation, returning Replicant-style Hiccup."
  (:require [cljs.pprint :refer [pprint]]
            [clojure.string :as str]
            [reitit.impl :refer [form-decode]]
            [dk.cst.prayer.search :as search]
            [dk.cst.prayer.static :as static]
            [dk.cst.prayer.web :as page]
            [dk.cst.prayer.web.frontend.event :as event]
            [dk.cst.prayer.web.frontend.state :refer [state]]))

(defn pp
  [x]
  (with-out-str (pprint x)))

(defn dev-view []
  [:div
   [:button {:on {:click [::event/reset-state]}}
    "reset"]
   [:details [:summary "state"]
    [:pre (pp (dissoc @state :entities :cached))]]])

(defn header-view []
  (let [{:keys [location]} @state
        {:keys [name]} location]
    [:header.top
     [:nav
      [:ul
       [:li [:a (when-not (= name ::page/main) {:href "/"}) "Home"]]
       [:li [:a (when-not (= name ::page/text-index) {:href "/texts"}) "Texts"]]
       [:li [:a (when-not (= name ::page/manuscript-index) {:href "/manuscripts"}) "Manuscripts"]]
       [:li [:a (when-not (= name ::page/work-index) {:href "/works"}) "Works"]]]
      [:form {:on {:submit [::event/search]}}
       [:input#searchbar {:on          {:focus [::event/select]}
                          :value       (when (= name ::page/search)
                                         (-> (get-in location [:params :query])





                                             ;; TODO: the decoding should occur elsewhere, not here






                                             (form-decode)))
                          :placeholder "search"
                          :type        "search"
                          :name        "query"}]]]]))

(defn locus-view
  [[from to]]
  (str from " — " to))

(defn list-view
  [coll]
  (interpose ", " (sort coll)))

(defn locus-parts
  "Split a `locus-str` into its constituent parts for comparison purposes."
  [locus-str]
  (if (string? locus-str)
    (some-> (re-matches #"(\d+)([rv])?" locus-str)
            ((fn [[_ n rv]]
               [(parse-long n) rv])))
    ;; In case of missing data, we put these cases at the end.
    ;; TODO: log the missing data
    [9999 "v"]))

;; This assumes we have put :tei/from and :tei/to into a vector in :tei/locus.
(def locus-order
  (juxt (comp locus-parts first :tei/locus)
        (comp locus-parts second :tei/locus)))

(declare table-views)

(defn in
  [x y]
  (if (set? x)
    (get x y)
    (= x y)))

(defn label-view
  [k v]
  (let [label (get-in static/labels [k v] (str v))]
    label))

(defn basic-field-search
  [k v]
  (if (set? v)
    (interpose ", " (map (partial basic-field-search k) (sort v)))
    (let [v' (if (re-find #"\s" v)
               (str "\"" v "\"")
               v)]
      [:a {:title (str "Find more with this field value")
           :href  (str "/search/" (str (name k) "=" v'))}
       (label-view k v)])))

(defn table-tr-view
  [bedebok-type [k v]]
  ;; NOTE: we are overloading the :bedebok/type value with keywords to better
  ;; decipher other keys during the recursive build of the frontend views.
  (when-not (and (= k :bedebok/type)
                 (keyword? v))
    [:tr
     [:td (when-let [doc (static/attr-doc k)]
            {:title doc})
      (name k)]
     [:td (condp in k
            #{:tei/msItem :tei/collationItem}
            (table-views (map #(assoc % :bedebok/type k) v))

            :tei/locus
            (locus-view v)

            :tei/dimensions
            (let [{:keys [tei/height tei/width]} v
                  ratio (if (and height width)
                          (/ height width)
                          1)]
              [:table.dimensions
               [:tr
                [:td]
                [:td.dimensions-width width]]
               [:tr
                [:td.dimensions-height height]
                [:div.dimensions-model {:style {:height (* ratio 100)
                                                :width  100}}]]])

            :tei/author
            (let [{:keys [tei/key tei/title]} v]
              [:a {:href  (str "/search/" (str "author=" key))
                   :title "Find more with this author"}
               title])

            :tei/origDate
            (let [{:keys [tei/notAfter tei/notBefore tei/title]} v]
              (if title
                (list title [:br] " (c. " notBefore "–" notAfter ")")
                (str "c. " notBefore "–" notAfter)))

            :tei/origPlace
            (let [{:keys [tei/key tei/title]} v]
              [:a {:href  (str "/search/" (str "origPlace=" key))
                   :title "Find more with this place"}

               title])

            :tei/supportDesc
            (let [{:keys [tei/support tei/material]} v]
              [:a {:href  (str "/search/" (str "material=" material))
                   :title "Find more with this material"}
               support])

            :tei/respStmt
            (let [{:keys [tei/key tei/resp tei/persName]} v
                  year (:tei/when v)]
              (list
                resp [:br]
                " ("
                (when year
                  (str year " "))
                (if key
                  [:a {:href  (str "/search/" (str "resp=" key))
                       :title "Find more with this person"}
                   persName]
                  resp)
                ")"))

            :bedebok/work
            (let [{:keys [tei/key tei/title]} v]
              [:a {:href  (str "/works/" key)
                   :title "View documents referencing this work"}
               title])

            :tei/corresp
            [:a (if (= bedebok-type "text")
                  {:href  (str "/manuscripts/" v)
                   :title "View corresponding manuscript"}
                  {:href  (str "/texts/" v)
                   :title "View corresponding text"})
             v]

            #_:bedebok/type
            #_[:a {:href  (str "/" bedebok-type "s")
                   :title "View more of this type"}
               v]

            ;; TODO: need the inline language info here too
            #{:tei/rubric :tei/incipit :tei/explicit}
            (if (= (count v) 1)
              (first v)
              [:ul
               (for [s (sort v)]
                 [:li s])])

            #{:bedebok/type
              :tei/class
              :tei/settlement
              :tei/repository
              :tei/mainLang
              :tei/otherLangs}
            (basic-field-search k v)

            ;; Put simple inline tables here.
            #_:tei/origPlace
            #_[:table.common
               (map (partial table-tr-view k) v)]

            ;; else
            (if (set? v)
              (list-view (map (partial label-view k) v))
              (label-view k v)))]]))

(defn prepare-for-table
  "Modifies the data of `m` for table display."
  [{:keys [tei/from tei/to]
    :as   m}]
  ;; Some attributes are implementation details to be removed at every level.
  (cond-> (dissoc m :db/id :file/node :tei/from :tei/to :bedebok/id)
    (or from to) (assoc :tei/locus [from to])))

(declare table-views)

(defn table-view
  [{:keys [bedebok/type db/id tei/locus tei/rubric tei/incipit tei/explicit]
    :as   m}]
  (let [metadata-tr-view' (partial table-tr-view type)
        entity'           (prepare-for-table m)
        table-data        (some-> entity'
                                  (dissoc :file/node
                                          :tei/text
                                          :tei/msItem
                                          :tei/collationItem
                                          :file/name
                                          :tei/locus
                                          :tei/rubric
                                          :tei/incipit
                                          :tei/explicit)
                                  (not-empty))]
    [:table.common {:id (str "db-" (or id locus))}
     (if-let [locus (:tei/locus entity')]
       [:tr.header-row
        [:th {:colspan 2
              :title   (static/attr-doc :tei/locus)}
         (locus-view locus)]]
       (if-let [filename (:file/name entity')]
         [:tr.header-row
          [:th {:colspan 2
                :title   (static/attr-doc :file/name)}
           filename]]
         [:tr.header-row
          [:th {:colspan 2}]]))

     ;; General metadata comes immediately after the header.
     (map metadata-tr-view' (sort-by first table-data))

     ;; Text summaries are put in the expected order as the final 2-column rows.
     (->> [[:tei/rubric rubric]
           [:tei/incipit incipit]
           [:tei/explicit explicit]]
          (remove (comp nil? second))
          (map metadata-tr-view'))

     ;; Embedded items come last.
     (when-let [manuscripts (:tei/msItem entity')]
       [:tr.msitem-row
        [:td {:colspan 2}
         [:details
          [:summary "Parts"]
          (table-views (map #(assoc % :bedebok/type :tei/msItem) manuscripts))]]])
     (when-let [collation (:tei/collationItem entity')]
       [:tr.collation-row
        [:td {:colspan 2}
         [:details
          [:summary "Parts"]
          (table-views (map #(assoc % :bedebok/type :tei/collationItem) collation))]]])]))

;; For displaying msItems and collation.
(defn table-views
  [ms]
  (->> ms
       (map prepare-for-table)
       (sort-by locus-order)
       (map table-view)
       (interpose [:div.continuation "︙"])))

(defn controls-view
  [id]
  (let [{:keys [location] :as state'} @state
        pages      (get-in state' [:cached id :pages])
        page-count (count pages)
        n          (get-in state' [:user :entities id :n] 0)]
    [:section.tei-page-controls
     [:button {:on       {:click [::event/page id :backward]}
               :disabled (= n 0)}
      "←"]
     [:select {:default-value n
               :on            {:change [::event/page id]}}
      (for [i (range page-count)]
        [:option {:value    i
                  :selected (= n i)}
         (inc i) " / " page-count])]
     [:button {:on       {:click [::event/page id :forward]}
               :disabled (= (inc n) page-count)}
      "→"]]))

(defn page-view
  [[pb content]]
  (let [data-n (-> pb first second :data-n)]
    (into [:article.tei-page [:header.tei-page-header data-n]
           [:section.tei-page-content content]])))

(defn pages-view
  [id]
  (let [state'        @state
        {:keys [n]} (get-in state' [:user :entities id])
        pages         (get-in state' [:cached id :pages])
        pages-display (boolean (get-in state' [:user :prefs :pages-display]))]
    (if pages-display
      [:section.tei-pages (map page-view pages)]
      [:section.tei-pages (controls-view id) (page-view (nth pages (or n 0)))])))

(defn uncapitalize
  [s]
  (str (str/lower-case (subs s 0 1))
       (subs s 1)))

(defn descriptive-view
  [{:keys [tei/origin tei/provenance tei/acquisition] :as entity}]
  (cond
    (or origin provenance)
    [:ul.descriptive
     (when origin
       [:li#origin
        [:strong {:title (static/attr-doc :tei/origin)}
         "ORIGIN"] ": "
        (uncapitalize origin)])
     (when provenance
       [:li#provenance
        [:strong {:title (static/attr-doc :tei/provenance)}
         "PROVENANCE"] ": "
        (uncapitalize provenance)])
     (when acquisition
       [:li#acquisition
        [:strong {:title (static/attr-doc :tei/acquisition)}
         "ACQUISITION"] ": "
        (uncapitalize acquisition)])]))

(defn page-header-view
  [{:keys [tei/title tei/summary tei/head bedebok/type]
    :as   entity}]
  [:header
   [:hgroup
    [:h1 title]
    (when summary
      [:p summary])]
   (when head
     [:p head])
   (descriptive-view entity)
   (when (= type "text")
     (let [pages-display (boolean (get-in @state [:user :prefs :pages-display]))]
       [:aside.preferences
        [:label [:input {:type    "checkbox"
                         :title   "Toggle single/multi page view"
                         :checked pages-display
                         :on      {:change [::event/pages-display]}}]
         " show all pages"]]))])

(defn- section
  [title view & [attr]]
  (when view
    [:section (when attr attr)
     [:h2 title]
     view]))

(defn pin-button
  [id]
  [:button.add-pin {:title "Pin content"
                    :on    {:click [::event/pin id]}}])

(defn unpin-button
  [id]
  [:button.remove-pin {:title "Unpin content"
                       :on    {:click [::event/pin id]}}])

(defn entity-view
  [{:keys [bedebok/type tei/msItem tei/collationItem bedebok/id]
    :as   entity}
   pin-status]
  (let [general    (some-> entity
                           ;; Removed ONLY at the top-level, since they are
                           ;; explicitly displayed in separate sections.
                           (dissoc :tei/title
                                   :tei/head
                                   :tei/summary
                                   :tei/origin
                                   :tei/provenance
                                   :tei/acquisition
                                   :tei/msItem
                                   :tei/collationItem)
                           (not-empty)
                           (table-view))
        manuscript (some->> msItem
                            (map #(assoc % :bedebok/type :tei/msItem))
                            (not-empty)
                            (table-views))
        collation  (some->> collationItem
                            (map #(assoc % :bedebok/type :tei/collationItem))
                            (not-empty)
                            (table-views))]
    [:article
     (case pin-status
       :unpinned (pin-button id)
       :pinned (unpin-button id)
       nil)
     (page-header-view entity)

     ;; When viewing a text, the pages are centred; when viewing a manuscript,
     ;; the manuscript items are centred.
     (if (= type "text")
       [:section.content.text
        (section "Pages" (pages-view id))
        [:aside.metadata
         (section "Miscellaneous" general)
         (section "Manuscript Item" manuscript)
         (section "Collation Data" collation)]]

       [:section.content.manuscript
        (section "Manuscript Items" manuscript)
        [:aside.metadata
         (section "Miscellaneous" general)
         (section "Collation Data" collation)]])]))

(defn work-view
  [id work]
  (let [n (count work)]
    [:article
     ;; TODO: needs a proper label
     [:header
      [:hgroup
       [:h1 id]
       [:p "References to this work"]]]
     [:section.list.single
      (section (case n
                 0 "No results"
                 1 "One result"
                 (str n " results"))
               [:dl.index
                (for [[doc-type ks] (sort-by first work)]
                  (list
                    [:dt {:id char} (str/capitalize doc-type)]
                    [:dd
                     [:ul
                      (for [k (sort ks)]
                        [:li
                         [:a {:href (str "/" doc-type "s/" k)}
                          k]])]]))])]]))

(defn search-view
  [query search-result]
  (let [n (count search-result)]
    [:article
     [:header
      [:hgroup
       [:h1 "Search result"]
       [:p query
        ;; TODO: visualise search query?
        #_(str (search/simplify (search/parse query)))]]
      (case n
        0 [:p "No documents match this query."]
        1 [:p "The following document matches this query:"]
        [:p "The following " n " documents match this query:"])]
     [:section.list
      [:dl.index
       (for [[type hits] (group-by :bedebok/type search-result)]
         (list
           [:dt (str/capitalize type)]
           [:dd
            [:ul
             (for [{:keys [bedebok/id]} (sort-by :bedebok/id hits)]
               [:li [:a {:href (str "/" type "s/" id)} id]])]]))]]]))

(defn alphabetical
  [s]
  (and (re-matches #"[\w]" s)
       (re-matches #"[^\d]" s)))

(defn skiplinks-table-view
  [header ks]
  [:table.common.skiplinks
   [:tr
    [:th {:colspan 4}
     header]]
   (for [ks' (partition 4 4 [nil nil nil] ks)]
     [:tr
      (for [k ks']
        (if (nil? k)
          [:td.empty]
          [:td [:a {:href (str "#" k)} k]]))])])

(defn index-section
  [heading type-plural char->kvs]
  (section heading
           [:dl.index
            (for [[char kvs] char->kvs]
              (list
                [:dt {:id char} char]
                [:dd
                 [:ul
                  (for [[k v] (sort-by second kvs)]
                    [:li [:a {:href (str "/" type-plural "/" k)} v]])]]))]))

(defn index-view
  [type index]
  (let [char->kvs   (->> (group-by (comp first second) index)
                         (sort-by first))
        other-char  (complement alphabetical)
        letter->kvs (not-empty (filter (comp alphabetical first) char->kvs))
        other->kvs  (not-empty (filter (comp other-char first) char->kvs))
        type-plural (str type "s")]
    [:article
     [:header
      [:h1 (str/capitalize type-plural)]]
     [:section.list
      [:section.listings
       (when letter->kvs
         (index-section "Alphabetical" type-plural letter->kvs))
       (when other->kvs
         (index-section "Other" type-plural other->kvs))]
      [:aside.skiplinks
       (section
         "Index"
         (list
           (when letter->kvs
             (skiplinks-table-view "Alphabetical" (map first letter->kvs)))
           (when other->kvs
             (skiplinks-table-view "Other" (map first other->kvs)))))]]]))

(defn frontpage-view
  []
  [:article
   [:header
    [:h1.brutalist "When " [:br] [:span.big.red "Danes"] [:br] " Prayed in " [:br] [:span.big.yellow "German"]]]
   [:p "This project examines the role of Low German in the transition from Latin to Danish as the primary language of religious devotion."]
   [:p "A common misconception holds that religious devotion was practiced solely through the medium of Latin until the Reformation. However, devotional books already began to appear in the vernacular in Denmark during the Middle Ages; not only in Danish, but also in another vernacular, Low German."]])

(defn content-view
  []
  (let [{:keys [location user] :as state'} @state
        {:keys [pins]} user
        {:keys [name params]} location
        {:keys [query id]} params
        ;; The only other pin-status is :pinned for the pinned/copied content
        ;; and this is set manually for the pinned content.
        pin-status (when (empty? (filter #{id} pins))
                     :unpinned)]
    (condp = name
      ::page/main (frontpage-view)
      ::page/search (search-view query (get-in state' [:search query]))
      ::page/work (work-view id (get-in state' [:works id]))
      ::page/text (entity-view (get-in state' [:entities id]) pin-status)
      ::page/manuscript (entity-view (get-in state' [:entities id]) pin-status)
      ::page/text-index (index-view "text" (get-in state' [:index "text"]))
      ::page/manuscript-index (index-view "manuscript" (get-in state' [:index "manuscript"]))
      ::page/work-index (index-view "work" (get-in state' [:index "work"])))))

(defn footer-view
  []
  [:footer
   [:address.grey
    [:div.big.black "© 2025"]
    "Department of Nordic Studies and Linguistics" [:br]
    "University of Copenhagen" [:br]
    "Emil Holms Kanal 2, DK-2300 Copenhagen S"]
   [:ul.links
    [:li
     [:a {:href  ""                                         ;TODO
          :title "Accessibility statement"}
      "Accessibility"]]
    [:li
     [:a {:href  ""                                         ;TODO
          :title "Privacy policy"}
      "Privacy"]]
    [:li
     [:a {:href  "https://nors.ku.dk/english/research/projects/when-danes-prayed-in-german/"
          :title "Official project page"}
      "Project page"]]
    [:li
     [:a {:href  "https://github.com/bedebok/prayer-app"
          :title "Source code"}
      "Github"]]]])

(defn pinning-view []
  (let [{:keys [user entities] :as state'} @state
        pins (:pins user)]
    [:section.pinning
     [:p "Pinned content: "]
     (for [id pins]
       (let [{:keys [tei/title bedebok/type]} (get entities id)]
         [:label {:id    (str id "-checkbox")
                  :title (str "Unpin: " id)}
          [:input {:type    "checkbox"
                   :on      {:change [::event/pin id]}
                   :checked true}]
          (or title id)]))
     [:button.remove-pin {:title "Unpin all"
                          :on    {:click [::event/reset-pins]}}]]))

;; TODO: make more functional, e.g. send/log errors somewhere on request
(defn error-message-view
  [{:keys [name message url body] :as error}]
  (when error
    [:dialog.error {:open true}
     [:h1 (or name "Unknown error")]
     (when message
       [:p [:strong "Message: "] (str/capitalize message)])
     (when url
       [:p [:strong "URL: "] [:a {:href url} url]])
     (when body
       [:pre (str body)])
     [:form {:method "dialog"}
      [:button {:autofocus true
                :on        {:click [::event/reset-error]}}
       "OK"]]]))

(defn page
  []
  (let [{:keys [user error] :as state'} @state
        {:keys [pins]} user]
    ;; Some kind of ID is needed for replicant to properly re-render
    ;; TODO: is there a better ID?
    [:div.container {:class (if (empty? pins)
                              "single-document"
                              "multi-document")}
     (header-view)
     #_(dev-view)
     (error-message-view error)
     #_[:button {:on {:click [::event/throw {:name    "Artificial error"
                                             :message "Error induced by user"}]}}
        "throw error"]
     [:div.page-body-wrapper
      [:div.spacer]
      [:aside.spine {:aria-hidden "true"}
       "When " [:span.red "Danes"] " Prayed in " [:span.yellow "German"]]
      [:section.page-body
       (if (empty? pins)
         [:main {:id js/window.location.pathname}
          (content-view)]
         (list
           (pinning-view)
           [:main {:id js/window.location.pathname}
            (content-view)
            (for [id pins]
              (entity-view (get-in state' [:entities id])
                           :pinned))]))
       (footer-view)]
      [:div.spacer]]]))
