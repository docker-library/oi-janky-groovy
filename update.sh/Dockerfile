FROM buildpack-deps:bullseye-scm

RUN set -eux; \
	apt-get update; \
	apt-get install -y --no-install-recommends \
		bzip2 \
		gawk \
		jq \
	; \
	rm -rf /var/lib/apt/lists/*

# https://github.com/docker-library/bashbrew/releases
ENV BASHBREW_VERSION 0.1.3
RUN set -eux; \
	git clone --quiet --depth 1 https://github.com/docker-library/bashbrew.git /tmp/bashbrew; \
	bashbrewArch="$(/tmp/bashbrew/scripts/bashbrew-host-arch.sh)"; \
	rm -rf /tmp/bashbrew; \
	wget -O /usr/local/bin/bashbrew "https://github.com/docker-library/bashbrew/releases/download/v${BASHBREW_VERSION}/bashbrew-$bashbrewArch"; \
	chmod +x /usr/local/bin/bashbrew; \
	bashbrew --version
