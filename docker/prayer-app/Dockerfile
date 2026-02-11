FROM ubuntu:24.04

RUN apt-get clean && \
    apt-get update && \
    DEBIAN_FRONTEND=noninteractive \
    apt-get install --no-install-recommends -y \
    git \
    npm \
    default-jdk \
    curl \
    rlwrap \
    locales \
    sed \
    libgomp1 \
    g++-12 \
    gcc-12

# Fix emojis not showing up on HTML pages
# https://medium.com/quiq-blog/handling-emoticons-or-other-unicode-characters-beware-of-docker-images-f0f11673dac4
RUN locale-gen en_US.UTF-8
ENV LANG en_US.UTF-8
ENV LANGUAGE en_US:en
ENV LC_ALL en_US.UTF-8

# The recommended way of installing Clojure CLI tools
# https://clojure.org/guides/getting_started#_installation_on_linux
RUN curl -L -O https://github.com/clojure/brew-install/releases/download/1.12.4.1602/linux-install.sh
RUN chmod +x linux-install.sh
RUN ./linux-install.sh

WORKDIR /etc/prayer-app

# Install JS dependencies
RUN npm init -y
RUN npm install -g shadow-cljs@3.1.7
RUN npm install shadow-cljs@3.1.7

# Fetch backend dependencies
COPY ./deps.edn /etc/prayer-app/deps.edn
RUN --mount=type=cache,target=/root/.m2 \
    --mount=type=cache,target=/root/.gitlibs \
    clojure -A:backend -Spath

# Fetch frontend dependencies
COPY ./shadow-cljs.edn /etc/prayer-app/shadow-cljs.edn
RUN --mount=type=cache,target=/root/.m2 \
    --mount=type=cache,target=/root/.gitlibs \
    --mount=type=cache,target=/root/.npm \
    shadow-cljs --aliases :frontend classpath

# Prepare frontend app source files
COPY ./src /etc/prayer-app/src
COPY ./resources /etc/prayer-app/resources

#  Create a release build of the frontend app
RUN --mount=type=cache,target=/root/.m2 \
    --mount=type=cache,target=/root/.gitlibs \
    shadow-cljs --aliases :frontend release app

# Build an uberjar for the backend
COPY ./build.clj /etc/prayer-app/build.clj
RUN --mount=type=cache,target=/root/.m2 \
    --mount=type=cache,target=/root/.gitlibs \
    clj -T:build uber

# Prepare the start script
COPY ./docker/prayer-app/start.sh /etc/prayer-app/start.sh
RUN chmod +x /etc/prayer-app/start.sh

# Switch to a non-root user
# NOTE: the ownership of the volumes must be set to group id 1024 on the host!
# https://medium.com/@nielssj/docker-volumes-and-file-system-permissions-772c1aee23ca
RUN addgroup --gid 1024 prayer-app
RUN adduser --disabled-password --gecos "" --force-badname --ingroup prayer-app prayer-app-user
RUN mkdir -p /etc/prayer-app/db && chown -R prayer-app-user:prayer-app /etc/prayer-app
USER prayer-app-user

# Run the backend web service
ENTRYPOINT ["./start.sh"]
