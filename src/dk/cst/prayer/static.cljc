(ns dk.cst.prayer.static)

;; from https://github.com/bedebok/Data/blob/main/Catalogue/xml/README.org
(def settlement
  {"KBH" "Copenhagen"
   "STH" "Stockholm"
   "LND" "Lund"
   "LIN" "Linköping"
   "ROS" "Roskilde"
   "KAL" "Kalmar"
   "UPS" "Uppsala"})

;; from https://github.com/bedebok/Data/blob/main/Catalogue/xml/README.org
(def repository
  {"AMS" "Arnamagnæan Collection"
   "KBK" "Royal Danish Library"
   "KBS" "National Library of Sweden"
   "KBB" "Karen Brahe Library"
   "KSB" "Kalmar City Library"
   "LSB" "Linköping City Library"
   "LUB" "Lund University Library"
   "UUB" "Uppsala University Library"})

(defn sdas
  [d f]
  (prn 123))
