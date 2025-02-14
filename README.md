# Sketch: Network Modelling

## Why

- network systems are hard to reason about before built
- can't test distributed assertions until built
- hard to uncover design bugs like race conditions
- systems grow in complexity quickly
- hard to think about high level system
- initial naming is easy but also easy to evolve without a naming standard
- hard to communicate designs before built

## How

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
        - rewrite-clj diffs schemas and adds missing keywords in alphabetical order
    - validate
        - schema names stick to naming standard
        - schema keywords follow idioms e.g. kebab-case everywhere
        - decoders (h/t Malli) for non-clojure keys
    - generate diagrams
        - state stores
        - data flow events
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

## Results

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

## Influences

https://github.com/clj-commons/rewrite-clj/blob/main/doc/01-user-guide.adoc
