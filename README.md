# Prayer app

## Data modeling
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

Seán Vrieland maintains overviews of the TEI files too:

- [Prayers](https://github.com/bedebok/Data/blob/main/Prayers/xml/README.org)
- [Catalogue](https://github.com/bedebok/Data/blob/main/Catalogue/xml/README.org)

Some important takeways from Seán's writings:

> Note that in both sections attributes will be used to point to elements in 
> other files. In catalogue files and text description files these attributes
> are tagged as @xml:id. In text edition files, on the other hand, these are 
> tagged as @corresp (for manuscript shelfmarks) or @key (for titles of texts).

So `xml:id` refers to a shelfmark ID while `corresp` references a shelfmark ID.
Quite a subtle difference! Not really sure what to make of it.

The `key` attribute is different in that it  references a known text that isn't
a part of the corpus.

## Architecture
The general design of the system is as a single-page app where routing takes place entirely on the frontend, except when the backend API needs to be accessed via HTTP requests.

The API is based on a few routes which transmit transit-encoded EDN data between client and server.

The database only needs to be read-only, so it is ephemeral, created on-demand on system boot and based on a directory of input XML files.

### Library choices
I am compiling [a list of various libraries under consideration](https://github.com/stars/simongray/lists/when-danes-prayed-in-german).

#### Pedestal
I am using Pedestal again since I have much experience with it, so there is a lot of code and experience that can be re-applied.

#### Reitit
I use Reitit in the frontend since it is the mainstream choice for a full-featured frontend SPA router + I have previous experience using it.

The use of Reitit in the backend this time (as opposed to Pedestal's router) is primarily motivated by Reitit's improved handling of wildcard routes, such that I don't need to prepend every SPA route with e.g. `/app` to delegate everything but the API to the frontend.

Another advantage of Reitit in the backend is the Metosin library ecosystem built around it. Many of these could have relevant applications.

#### Transito
I am using Transito again since its primary value proposition (CLJV encoding/decoding of transit) still applies.

I could have used Metosin's [Muuntaja](https://github.com/metosin/muuntaja) to encode/decode on the server, however that still leaves the frontend lacking an implementation.

#### Datalevin
I considered using Asami again, but since there is no hard requirement for RDF and since the data is less reliant on linked entities than Glossematics, it didn't seem like the best choice of database.

Datalevin has the advantage of having a similar query language to Asami, while also providing full-text searches and "easy" reconstruction of input document entities, similar to how Datomic does it.

It is probably a bit more unstable than Asami, e.g. I already discovered two (what I would call) bugs and a core missing feature. However, the developer behind Datalevin is pretty quick and responsive towards issues/feature requests.

#### Replicant
TBD
