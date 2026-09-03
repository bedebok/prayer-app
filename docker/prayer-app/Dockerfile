FROM ubuntu:24.04 AS build

RUN apt-get update && \
    DEBIAN_FRONTEND=noninteractive \
    apt-get install --no-install-recommends -y \
    git \
    npm \
    default-jdk-headless \
    curl \
    ca-certificates \
    locales && \
    rm -rf /var/lib/apt/lists/*

# Fix emojis not showing up on HTML pages
# https://medium.com/quiq-blog/handling-emoticons-or-other-unicode-characters-beware-of-docker-images-f0f11673dac4
RUN locale-gen en_US.UTF-8
ENV LANG=en_US.UTF-8
ENV LANGUAGE=en_US:en
ENV LC_ALL=en_US.UTF-8

# The recommended way of installing Clojure CLI tools
# https://clojure.org/guides/getting_started#_installation_on_linux
RUN curl -L -O https://github.com/clojure/brew-install/releases/download/1.12.6.1673/linux-install.sh && \
    chmod +x linux-install.sh && \
    ./linux-install.sh

WORKDIR /etc/prayer-app

# Install JS dependencies (the shadow-cljs version comes from package.json)
COPY ./package.json ./package-lock.json /etc/prayer-app/
RUN --mount=type=cache,target=/root/.npm \
    npm ci

# Fetch backend dependencies
COPY ./deps.edn /etc/prayer-app/deps.edn
RUN --mount=type=cache,target=/root/.m2 \
    --mount=type=cache,target=/root/.gitlibs \
    clojure -P && clojure -P -T:build

# Fetch frontend dependencies
COPY ./shadow-cljs.edn /etc/prayer-app/shadow-cljs.edn
RUN --mount=type=cache,target=/root/.m2 \
    --mount=type=cache,target=/root/.gitlibs \
    --mount=type=cache,target=/root/.npm \
    npx shadow-cljs --aliases :frontend classpath

# Prepare frontend app source files
COPY ./src /etc/prayer-app/src
COPY ./resources /etc/prayer-app/resources

#  Create a release build of the frontend app
RUN --mount=type=cache,target=/root/.m2 \
    --mount=type=cache,target=/root/.gitlibs \
    npx shadow-cljs --aliases :frontend release app

# Build an uberjar for the backend
COPY ./build.clj /etc/prayer-app/build.clj
RUN --mount=type=cache,target=/root/.m2 \
    --mount=type=cache,target=/root/.gitlibs \
    clojure -T:build uber


# The runtime image contains only a JRE and the uberjar; build tools and source
# code stay in the build stage to minimise the attack surface.
FROM ubuntu:24.04

# NOTE: Datalevin 1.x requires Java 21+; libgomp1 is used by its native code.
RUN apt-get update && \
    DEBIAN_FRONTEND=noninteractive \
    apt-get install --no-install-recommends -y \
    openjdk-21-jre-headless \
    libgomp1 \
    locales && \
    rm -rf /var/lib/apt/lists/* && \
    locale-gen en_US.UTF-8

ENV LANG=en_US.UTF-8
ENV LANGUAGE=en_US:en
ENV LC_ALL=en_US.UTF-8

WORKDIR /etc/prayer-app

COPY --from=build /etc/prayer-app/prayer-app.jar /etc/prayer-app/prayer-app.jar

# Prepare the start script
COPY ./docker/prayer-app/start.sh /etc/prayer-app/start.sh
RUN chmod +x /etc/prayer-app/start.sh

# Switch to a non-root user
# NOTE: the ownership of the volumes must be set to group id 1024 on the host!
# https://medium.com/@nielssj/docker-volumes-and-file-system-permissions-772c1aee23ca
RUN groupadd --gid 1024 prayer-app && \
    useradd --create-home --gid prayer-app prayer-app-user && \
    mkdir -p /etc/prayer-app/db && \
    chown -R prayer-app-user:prayer-app /etc/prayer-app
USER prayer-app-user

# Run the backend web service
ENTRYPOINT ["./start.sh"]
