# Prayer app

## Design

### Data modeling
A lot of the data is described in the form of the [TEI Manuscript Description](https://tei-c.org/release/doc/tei-p5-doc/en/html/MS.html) element and its associated subelements.

The project defines the following entities which map to the TEI standard in the following way:

* **manuscript**: a physical object that contains one or more **texts**.
  * In TEI → the `msIdentifier` tag contains information used to identify the manuscript.
* **text**: an instance of a **work** located within a **manuscript**.
  * In TEI →  the `msItem` tag (located within `msContents`) contains information used to identify a **text**.
* **work**: a class of **text** instances.
  * In TEI → the `msItem` tag references the relevant **work** using an `xml:id` for a specific TEI document or the `key` attribute for a known, non-file **work** (if applicable).
* **catalogue**: a digital document created by the researchers that contains either a detailed table of **text**, **work**, or **manuscript** entities.
  * In TEI → the manuscript tags are repurposed for this special entity, such that `msIdentifier` is used to identify the **catalogue** entity and `msItem` contains each item/row in the table.

Seán Vrieland maintains an [overview of the TEI files](https://github.com/bedebok/Data/blob/main/Prayers/xml/README.org) too.

### Architecture
I am compiling [a list of various libraries under consideration](https://github.com/stars/simongray/lists/when-danes-prayed-in-german).
