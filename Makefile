.PHONY: all run start stop down restart status ps logs build install help

all: start

run: start

start:
	@./souklab.sh start

stop:
	@./souklab.sh stop

down:
	@./souklab.sh down

install:
	@./souklab.sh install

restart:
	@./souklab.sh restart

status:
	@./souklab.sh status

ps:
	@./souklab.sh status

logs:
	@./souklab.sh logs $(filter-out $@,$(MAKECMDGOALS))

build:
	@./souklab.sh start --build

help:
	@./souklab.sh help

# Catch-all to allow arguments to logs like `make logs app`
%:
	@:
