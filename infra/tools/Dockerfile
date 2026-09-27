# The command-line tools the repository's scripts need (bash, curl, jq, make, python3, the Docker CLI with
# Compose), in one small image, so a host needs nothing but Docker to run them. Used through the compose profile
# `tools`: `.\marvel` on Windows runs every script in it; on macOS/Linux it is optional.
FROM docker:29.8.1-cli

RUN apk add --no-cache bash curl jq make python3 socat

COPY entrypoint.sh /usr/local/bin/entrypoint.sh
RUN chmod +x /usr/local/bin/entrypoint.sh

WORKDIR /work
ENTRYPOINT ["/usr/local/bin/entrypoint.sh"]
CMD ["bash"]
