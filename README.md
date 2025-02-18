# Sketch: Network Modelling

Clojure software architects often say that we add schemas "at the boundaries"

Sketch allows you to model and test the entire system with those boundaries, before building and deploying any
components.

[Github Repo](https://github.com/nextdoc/sketch)

### Demo

[This test](https://github.com/nextdoc/sketch/blob/main/examples/mobile_weather_app/happy_path_test.clj)
generates [this static html file](https://nextdoc.github.io/sketch/mobile-weather-app/happy-path-test.html) when run

The actors and the step labels are clickable and this will display the state stored by each actor at any step in the
test.
This makes it a very effective storytelling tool.

You can clone the repo and run it yourself locally if you want to play with it.
If you do, try this:

- Change one of the emitted messages and see that data-flow is validated by the test
- Change some of the data that is persisted to storage and see that state is validated by the test
- Comment out some of the test steps and see the diagram change when the test is run
- Start the model watcher (top of test)
  - uncomment the :delete-me request in weather-model and see the changes in weather-registry.cljc

### Why

- network systems are hard to reason about before built
- can't test distributed assertions until built
- hard to uncover design bugs like race conditions, cache invalidation, etc
- distributed systems grow in complexity quickly
- hard to think about high level system
- initial naming is easy but also easy to diverge from initial naming standard
- hard to communicate designs before built

### How

- start with EDN declaration of system shape
    - actors
    - state
    - domain entities
    - data flow events
        - request/response
        - unidirectional (web-socket or Queue)
    - schema key naming: source, target or event focused naming TODO
- tools
    - bootstrap Malli schema
        - manually populate schemas maintaining naming standard
    - sync Malli schema as db changes
        - file watcher
        - rewrite-clj diffs schemas and adds missing keywords in alphabetical order
    - validate
        - schema names stick to naming standard
        - schema keywords follow idioms e.g. kebab-case everywhere
        - decoders (h/t Malli) for non-clojure keys
    - generate diagrams
        - data flow sequence
        - distributed state changes after each data flow event
    - generate non-clojure code
        - Typescript/Zod TODO
        - Apex TODO
        - Malli
            - JSON Schema
            - Open API
- testing
    - composable steps tell the story of data flow
        - add network events as stories grow
    - composable chapters (step sequences)
    - state and data flow automatically checked using Malli schemas
    - state data flow for non-clojure systems use local idioms e.g. camelCase for JSON
    - assertions can be made about state anywhere e.g. check race conditions
    - create fns using domain data
        - add (optional) fn schemas to avoid data bugs
        - informs implementations later during build
    - verify decoders adapt data everywhere
    - artifacts
        - generated diagrams
            - sequence diagrams for each story/chapter/step
        - actor fns
        - decoders
    - run on jvm or node using cljc
        - jvm Malli DX tools better
    - exceptions
        - Cursive Seeker link to failing step

## Benefits

- design system before implementation
- reasoning about total system easier with diagrams (state and sequence)
- enforced naming standards means easier to remember
    - data flow events and names
    - schema names
- kebab-case keywords in all modelling and tests
    - less mistakes forgetting which key style is used where
- Malli schemas for state and data flow ready for implementations
- full inventory of network communications
- shift left on design bugs
- communicate with diagrams before initial build
- avoid naming standard drift
- generate non-clojure code if required

## Status

- Early alpha: likely to change but we'll try to avoid breaking changes to the test api
- Diagram app is a POC, needs [cleanup](https://github.com/nextdoc/sketch/issues/6)
- Very few tests
- Sparse documentation
- Limited bandwidth to work on it until [Nextdoc.io](https://nextdoc.io/) grows
