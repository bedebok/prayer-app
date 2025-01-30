(ns dk.cst.prayer.static)

(def schema
  {;; Meta attributes about the relevant file and XML nodes.
   :file/src          {:db/valueType :db.type/string
                       :db/doc       "The XML source code of the entity."}
   :file/name         {:db/valueType :db.type/string
                       :db/doc       "The filename of the document entity."}
   :file/node         {:db/doc "The Hiccup node that is the source of this entity."}

   ;; Core entity IDs and references.
   :bedebok/id        {:db/valueType :db.type/string
                       :db/unique    :db.unique/identity
                       :db/doc       (str "Used to identify each of the three core entities.")}
   :tei/corresp       {:db/valueType :db.type/string
                       :db/unique    :db.unique/identity
                       :db/doc       (str "Used to reference a text or manuscript within a manuscript or text.")}
   :tei/idno          {:db/valueType :db.type/string
                       :db/unique    :db.unique/identity
                       :db/doc       "<idno> (identifier) supplies any form of identifier used to identify some object, such as a bibliographic item, a person, a title, an organization, etc. in a standardized way. [14.3.1 Basic Principles2.2.4 Publication, Distribution, Licensing, etc.2.2.5 The Series Statement3.12.2.4 Imprint, Size of a Document, and Reprint Information]"}
   :tei/key           {:db/valueType :db.type/string
                       :db/doc       (str "Used to reference canonical works.")}

   ;; NOTE: keeping doc type separate from :tei/type as that expects a composite tuple.
   :bedebok/type      {:db/valueType :db.type/string}

   ;; Plain text stored for search.
   :bedebok/text      {:db/valueType   :db.type/string
                       :db/cardinality :db.cardinality/one
                       :db/fulltext    true
                       :db/doc         "The plain text of the document."}
   :bedebok/label     {:db/valueType   :db.type/string
                       :db/cardinality :db.cardinality/many
                       :db/fulltext    true
                       :db/doc         "A human-readable label for an entity."}

   :tei/title         {:db/valueType   :db.type/string
                       :db/cardinality :db.cardinality/one
                       :db/doc         "<title> (title) contains a title for any kind of work. [3.12.2.2 Titles, Authors, and Editors2.2.1 The Title Statement2.2.5 The Series Statement]"}
   :tei/settlement    {:db/valueType   :db.type/string
                       :db/cardinality :db.cardinality/one
                       :db/doc         "<settlement> (settlement) contains the name of a settlement such as a city, town, or village identified as a single geo-political or administrative unit. [14.2.3 Place Names]"}
   :tei/mainLang      {:db/valueType   :db.type/string
                       :db/cardinality :db.cardinality/one
                       :db/doc         "<textLang> (text language) describes the languages and writing systems identified within the bibliographic work being described, rather than its description. [3.12.2.4 Imprint, Size of a Document, and Reprint Information11.6.6 Languages and Writing Systems]"}
   :tei/repository    {:db/valueType   :db.type/string
                       :db/cardinality :db.cardinality/one
                       :db/doc         "<repository> (repository) contains the name of a repository within which manuscripts or other objects are stored, possibly forming part of an institution. [11.4 The Manuscript Identifier]"}
   :tei/support       {:db/valueType   :db.type/string
                       :db/cardinality :db.cardinality/one
                       :db/doc         "<support> (support) contains a description of the materials etc. which make up the physical support for the written part of a manuscript or other object. [11.7.1 Object Description]"}
   :tei/material      {:db/valueType   :db.type/string
                       :db/cardinality :db.cardinality/one
                       :db/doc         "<material> (material) contains a word or phrase describing the material of which the object being described is composed. [11.3.2 Material and Object Type]"}

   :tei/origDate      {:db/cardinality :db.cardinality/one
                       :db/doc         "<origDate> (origin date) contains any form of date, used to identify the date of origin for a manuscript, manuscript part, or other object. [11.3.1 Origination]"}
   :tei/origPlace     {:db/cardinality :db.cardinality/one
                       :db/valueType   :db.type/ref
                       :db/isComponent true
                       :db/doc         "<origPlace> (origin place) contains any form of place name, used to identify the place of origin for a manuscript, manuscript part, or other object. [11.3.1 Origination]"}

   :tei/collationItem {:db/cardinality :db.cardinality/many
                       :db/valueType   :db.type/ref
                       :db/isComponent true
                       :db/doc         "<collation> (collation) contains a description of how the leaves, bifolia, or similar objects are physically arranged. [11.7.1 Object Description]"}
   :tei/msItem        {:db/cardinality :db.cardinality/many
                       :db/valueType   :db.type/ref
                       :db/isComponent true
                       :db/doc         "<msItem> (manuscript item) describes an individual work or item within the intellectual content of a manuscript, manuscript part, or other object. [11.6.1 The msItem and msItemStruct Elements]"}
   :tei/class         {:db/cardinality :db.cardinality/many
                       :db/valueType   :db.type/string
                       :db/doc         "@class identifies the text types or classifications applicable to this item by pointing to other elements or resources defining the classification concerned."}
   :tei/dimensions    {:db/doc "<dimensions> (dimensions) contains a dimensional specification. [11.3.4 Dimensions]"}
   :tei/locus         {:db/doc "<locus> (locus) defines a location within a manuscript, manuscript part, or other object typically as a (possibly discontinuous) sequence of folio references. [11.3.5 References to Locations within a Manuscript]"}
   :tei/from          {:db/valueType   :db.type/string
                       :db/cardinality :db.cardinality/one}
   :tei/to            {:db/valueType   :db.type/string
                       :db/cardinality :db.cardinality/one}

   ;; These attributes form a sort of summary of a prayer.
   ;; TODO: should it one-to-one for each language?
   :tei/rubric        {:db/valueType   :db.type/string
                       :db/cardinality :db.cardinality/many
                       :db/doc         "<rubric> (rubric) contains the text of any rubric or heading attached to a particular manuscript item, that is, a string of words through which a manuscript or other object signals the beginning of a text division, often with an assertion as to its author and title, which is in some way set off from the text itself, typically in red ink, or by use of different size or type of script, or some other such visual device. [11.6.1 The msItem and msItemStruct Elements]"}
   :tei/incipit       {:db/valueType   :db.type/string
                       :db/cardinality :db.cardinality/many
                       :db/doc         "<incipit> contains the incipit of a manuscript or similar object item, that is the opening words of the text proper, exclusive of any rubric which might precede it, of sufficient length to identify the work uniquely; such incipits were, in former times, frequently used a means of reference to a work, in place of a title. [11.6.1 The msItem and msItemStruct Elements]"}
   :tei/explicit      {:db/valueType   :db.type/string
                       :db/cardinality :db.cardinality/many
                       :db/doc         "<explicit> (explicit) contains the explicit of a item, that is, the closing words of the text proper, exclusive of any rubric or colophon which might follow it. [11.6.1 The msItem and msItemStruct Elements]"}

   ;; TODO: maybe rename to :bedebok/entity since this't in the TEI standard?
   :tei/entity        {:db/cardinality :db.cardinality/one
                       :db/valueType   :db.type/ref
                       :db/doc         (str "For internal entities appearing across the documents "
                                            "such as named entities or biblical references. "
                                            "Multiple references to an entity are allowed, "
                                            "but only a single entity exists.")}
   :tei/name          {:db/valueType   :db.type/string
                       :db/cardinality :db.cardinality/one
                       :db/doc         "<name> (name, proper noun) contains a proper noun or noun phrase. [3.6.1 Referring Strings]"}
   :tei/type          {:db/valueType   :db.type/string
                       :db/cardinality :db.cardinality/one
                       :db/doc         "@type characterizes the element in some sense, using any convenient classification scheme or typology. "}
   :tei/name+type     {:db/valueType   :db.type/tuple
                       :db/tupleAttrs  [:tei/name :tei/type]
                       :db/cardinality :db.cardinality/one
                       :db/unique      :db.unique/identity}
   ;; TODO: use bedebok instead? I don't think this is from TEI.
   :tei/named         {:db/cardinality :db.cardinality/many
                       :db/valueType   :db.type/ref
                       :db/isComponent true
                       :db/doc         (str "A named entity reference.")}
   :tei/ref           {:db/cardinality :db.cardinality/many
                       :db/valueType   :db.type/ref
                       :db/isComponent true
                       :db/doc         "<ref> (reference) defines a reference to another location, possibly modified by additional text or comment. [3.7 Simple Links and Cross-References17.1 Links]"
                       #_(str "A literary reference.")}})

;; Sorting the results of (zipmap (map name (keys schema)) (keys schema))
(def field->attribute
  {"settlement" :tei/settlement
   #_#_"origDate" :tei/origDate                             ; TODO: subfield
   "class"      :tei/class
   "key"        :tei/key
   "repository" :tei/repository
   "support"    :tei/support
   "material"   :tei/material
   #_#_"locus" :tei/locus                                   ; TODO: subfield
   #_#_"origPlace" :tei/origPlace                           ; TODO: subfield
   "name"       :file/name
   #_#_"dimensions" :tei/dimensions                         ; TODO: subfield
   "corresp"    :tei/corresp
   "title"      :tei/title
   "type"       :bedebok/type
   #_#_"from" :tei/from                                     ;TODO: locus
   #_#_"to" :tei/to})                                       ;TODO: locus

(def attr-doc
  (reduce-kv (fn [m k v] (if-let [doc (:db/doc v)]
                           (assoc m k doc)
                           m))
             {} schema))

(def labels
  ;; https://github.com/bedebok/Data/blob/main/Catalogue/xml/README.org
  {:tei/settlement {"KBH" "Copenhagen"
                    "STH" "Stockholm"
                    "LND" "Lund"
                    "LIN" "Linköping"
                    "ROS" "Roskilde"
                    "KAL" "Kalmar"
                    "UPS" "Uppsala"}
   ;; https://github.com/bedebok/Data/blob/main/Catalogue/xml/README.org
   :tei/repository {"AMS" "Arnamagnæan Collection"
                    "KBK" "Royal Danish Library"
                    "KBS" "National Library of Sweden"
                    "KBB" "Karen Brahe Library"
                    "KSB" "Kalmar City Library"
                    "LSB" "Linköping City Library"
                    "LUB" "Lund University Library"
                    "UUB" "Uppsala University Library"}})
