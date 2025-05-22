# Prayer app

## Data modeling
A lot of the data is described in the form of the [TEI Manuscript Description](https://tei-c.org/release/doc/tei-p5-doc/en/html/MS.html) element and its associated sub-elements.

The project defines the following entities which map to the TEI standard in the following way:

* **manuscript**: a physical object that contains one or more **texts**.
  * In TEI → the `msIdentifier` tag contains information used to identify the manuscript.
* **text**: an instance of a **work** located within a **manuscript**.
  * In TEI →  the `msItem` tag (located within `msContents`) contains information used to identify a **text**.
* **work**: a class of **text** instances.
  * In TEI → the `msItem` tag references the relevant **work** using an `xml:id` for a specific TEI document or the `key` attribute for a known, non-file **work** (if applicable).

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

## Setup

### Development environment
* Development itself requires a JDK and Clojure. The system uses the Clojure CLI/deps.edn to organise the project dependencies.
* Datalevin (the database) requires a few native libraries to be present during development; see the [official page](https://github.com/juji-io/datalevin/blob/master/doc/install.md#native-dependencies) for more.

During development, the Pedestal server and Datalevin database are run through the REPL as is the standard for Clojure projects.

Navigate to the comment block of `dk.cst.prayer` to find the bit of code that boots the system. Keep in mind that the [bedebok/Data](https://github.com/bedebok/Data) project needs to be available at the path stated in `dk.cst.prayer.db`.

### Production environment
The production setup requires Docker. It consists of two separate Docker containers: one for running the system and one for running Caddy, acting as a reverse proxy.

When running the Docker compose setup, you must proide a directory of TEI files as an environmental variable:

```shell
PRAYER_APP_FILES_DIR=/Users/rqf595/Code/Data/Gold\ corpus docker compose up --build
```

> NOTE: if running Caddy alongside the system isn't desired (e.g. while testing locally) you can comment out that part from the `docker-compose.yml` file.


## Architecture

### Overview
The system is designed as a single-page app (**SPA**) where routing takes place entirely on the client/**frontend**. The backend server serves the same skeleton HTML page for any valid route, with the notable exception of the backend API which the SPA accesses via HTTP requests based on user actions.

The **backend API** routes all serve transit-encoded EDN data. These EDN data structures are decoded and stored in a local cache in the client's web browser using [localStorage](https://developer.mozilla.org/en-US/docs/Web/API/Window/localStorage).

The **database**, which is the source of all of the data that can be accessed through the API endpoints, can be entirely read-only, so it is treated as an ephemeral entity which is created on-demand on system boot and based on a directory of input XML files.

In the **production** system, this architecture is packaged as a single artifact within a Docker container. A separate Docker container contains a **reverse proxy**/gateway server which handles SSL certificates, compression and more general security features.

### Data and search
TODO...

### Technology choices
These are the core technologies/libraries chosen for this project along with some justification of their use.

#### Pedestal (web server and backend router)
I am using Pedestal once more to make the web service since I have much experience with it, so there is a lot of code and experience that can be re-applied.

#### Reitit (frontend router)
I use Reitit in the frontend since it is the mainstream choice for a full-featured frontend SPA router + I have previous experience using it.

While it would be nice to only focus on a single router model, the use of Reitit in the backend isn't advisable at this time. Reitit's does have an improved handling of wildcard routes and it handles trailing slashes by default, but it loses out in the following ways:

- poor handling of static files, at least when combined with Pedestal/interceptors
- no way to represent the routes as a function like in Pedestal (helps to shorten the development feedback loop)

One advantage of Reitit in the backend is the Metosin library ecosystem built around it, e.g. for coercion. Many of these could have relevant applications. It is possible that some of these could still be used directly with Pedestal, though.

#### Transito (encoding/decoding)
I am using Transito again since its primary value proposition (CLJV encoding/decoding of transit) still applies.

I could have used Metosin's [Muuntaja](https://github.com/metosin/muuntaja) to encode/decode on the server, however that still leaves the frontend lacking an implementation.

#### Datalevin (database)
I considered using Asami again, but since there is no hard requirement for RDF and since the data is less reliant on linked entities than Glossematics, it didn't seem like the best choice of database.

Datalevin has the advantage of having a similar query language to Asami, while also providing full-text searches and "easy" reconstruction of input document entities, similar to how Datomic does it.

It is probably a bit more unstable than Asami, e.g. I already discovered two (what I would call) bugs and a core missing feature. However, the developer behind Datalevin is pretty quick and responsive towards issues/feature requests.

#### Replicant (HTML generation)
I am using Replicant to render Hiccup in the frontend. It is similar in style to Reagent and Rum, which are both React-wrappers, but don't depend on React.

#### Telemere (logging)
This universal logging/metrics CLJ(S) library seems to be the best option today.

#### Instaparse (parsing)
I use Instaparse to parse my search query language EBNF.

#### Handling TEI
I use my own libraries for parsing and paginating TEI documents.

#### Caddy (reverse proxy & certificates)
Caddy is the simplest way to set up these things.
