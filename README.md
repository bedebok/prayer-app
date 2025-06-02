# When Danes Prayed in German
This is the source code for the website at [bedebog.dk](https://bedebog.dk).

The system indexes a directory of relevant TEI documents (old prayer books that have been digitized) in order to produce a website for exploring these documents interactively. The documents are made searchable via a common search query language. The website also provides additional features such as the ability to pin multiple documents and a way to customize the display of texts.


## Setup

### Production environment
The production setup requires Docker. It consists of two separate Docker containers: one for running the system and one for running Caddy (acting as a reverse proxy).

The production TEI files must all go somewhere into the `/opt/tei-files` directory. Whenever the system is (re)started it will look inside this directory for compatible files to build a new database from. This database is what populates the various pages of the website.

The current production version of the system is kept as a Git repo in `/opt/prayer-app` while a snapshot of the Data repository is kept in `/opt/Data`.

#### Systemd service
In the production environment, the Docker setup is ideally [run as a Systemd service](https://www.linode.com/docs/guides/introduction-to-systemctl/):

```
systemctl restart prayer
```

This will ensure that the service starts on boot if a third party, e.g. KU-IT, randomly restarts the server. A small set of Systemd commands form the basis of managing the website on the server.

> NOTE: this Systemd+Docker setup is pretty much exactly how I do it in several other projects, e.g. [DanNet](https://github.com/kuhumcst/DanNet/tree/master?tab=readme-ov-file#setup).

Prior to being run the first time, the service itself must be installed by copying over the relevant service file and enabling it:

```
cp system/prayer.service /etc/systemd/system/prayer.service
systemctl enable prayer
```

The current status of the service (i.e. a log snapshot) can be seen by running:

```
systemctl status prayer
```

#### Running Docker Compose directly
When running the Docker compose setup directly, you must provide the directory of TEI files as an environmental variable:

```shell
# assumes you are running this in /opt/prayer-app/docker
PRAYER_APP_FILES_DIR=/opt/tei-files docker compose up --build -d --remove-orphans
```

> NOTE: if running Caddy alongside the system isn't desired (e.g. while testing locally) you can comment out that part from the `docker-compose.yml` file.

### Development environment
* Development requires
  * a JDK + Clojure
  * NPM + shadow-cljs
* The system uses the Clojure CLI/deps.edn to organise the project dependencies.
* Datalevin (the database) requires a few native libraries to be present during development; see the [official page](https://github.com/juji-io/datalevin/blob/master/doc/install.md#native-dependencies) for more.

During development, the Pedestal server and Datalevin database are run through the REPL as is the standard for Clojure projects.

Navigate to the comment block of `dk.cst.prayer` to find the bit of code that boots the system. Keep in mind that the [bedebok/Data](https://github.com/bedebok/Data) project needs to be available at the path stated in `dk.cst.prayer.db`.

The frontend is developed using shadow-cljs hot-reloading:

```
# make a version of the frontend available on localhost:9876
npx shadow-cljs watch app
```

## Data modeling
The data displayed on the website comes from TEI documents that the researchers on the project have produced. A lot of this data is described in the form of the [TEI Manuscript Description](https://tei-c.org/release/doc/tei-p5-doc/en/html/MS.html) element and its associated sub-elements.

The project defines the following entities which map to the TEI standard in the following way:

* **manuscript**: a physical object that contains one or more **texts**.
  * In TEI → the `msIdentifier` tag contains information used to identify the manuscript.
* **text**: an instance of a **work** located within a **manuscript**.
  * In TEI →  the `msItem` tag (located within `msContents`) contains information used to identify a **text**.
* **work**: a class of **text** instances.
  * In TEI → the `msItem` tag references the relevant **work** using an `xml:id` for a specific TEI document or the `key` attribute for a known, non-file **work** (if applicable).

Seán Vrieland maintains overviews of the TEI files too in the project [Data repository](https://github.com/bedebok/Data). An important takeway from Seán's writings is:

> Note that in both sections attributes will be used to point to elements in
> other files. In catalogue files and text description files these attributes
> are tagged as @xml:id. In text edition files, on the other hand, these are
> tagged as @corresp (for manuscript shelfmarks) or @key (for titles of texts).

So `xml:id` refers to a shelfmark ID while `corresp` references a shelfmark ID.
Quite a subtle difference!

The `key` attribute is different in that it  references a known text that isn't
a part of the corpus.


## Architecture
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
