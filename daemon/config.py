import os


SERVER_HOST = os.environ.get(
    "EARTHQUACK_HOST",
    "100.92.160.31",
)

SERVER_PORT = int(
    os.environ.get(
        "EARTHQUACK_PORT",
        "8875",
    )
)

FILE_SERVER_PORT = int(
    os.environ.get(
        "EARTHQUACK_FILE_PORT",
        "8876",
    )
)


APP_NAME = "earthQuack"
APP_VERSION = "0.1.0"
