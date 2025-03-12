# Sketch: Network Modelling

Clojure software architects often say that we add schemas "at the boundaries"

Sketch allows you to model and test the entire system with those boundaries, before building and deploying any
components.

[Github Repo](https://github.com/nextdoc/sketch)

**Demo:** when you
run [this test](https://github.com/nextdoc/sketch/blob/main/examples/mobile_weather_app/happy_path_test.clj) it
generates the static HTML file below.

The actors and the step labels are clickable. They will display the state changes made each actor at any step.

The state diagrams are zoomable using your mouse wheel and can be dragged.

<a href="https://nextdoc.github.io/sketch/mobile-weather-app/happy-path-test.html">
  <!-- TODO switch sha to tag after next release -->
  <img src="https://cdn.jsdelivr.net/gh/nextdoc/sketch@06f553dc5b50306719e33f04f052384297d323c1/doc/example1.png" alt="Example diagram showing network interactions" class="diagram-image">
</a>

This provides an effective thinking / storytelling / discussion tool.

You can clone the repo and run it yourself locally if you want to play with it.
If you do, try this:

- Change one of the emitted messages and see that data-flow is validated by the test
- Change some of the data that is persisted to storage and see that state is validated by the test
- Comment out some of the test steps and see the diagram change when the test is run
- Start the model watcher (top of test)
    - uncomment the :delete-me request in weather-model and see the changes in weather-registry.cljc

## Why: what problems does this address?

**Complex Reasoning:** Understanding and predicting the behavior of distributed components is difficult.
Integration tests describing systems often become large and complex.
This complexity makes it difficult for developers to maintain a complete mental model of the entire test.

**Collaborative Design Difficulties:** Designing distributed systems requires effective collaboration among team
members. Many external factors increase the difficulty of sharing design knowledge.

**Schema Naming Drift:** Maintaining consistent naming conventions across distributed components is challenging. Over
time, schema keyword naming drift can occur, leading to greater cognitive load, misinterpretations and integration
issues between services.
Some projects have mixtures of keyword styles in domain functions which can lead to confusion.

**Opaque Integration Tests:** Integration tests are often crafted as sequential narratives that describe
system behavior. Typically only developers use them to understand the system. There are many other people who
could benefit from that understanding. With that knowledge, they can contribute more to the design process.

## How: what do you get?

There are two data flows provided by Sketch that are useful:

#### Malli Schema Generation

The core of the system design is described by
an [EDN/Aero file](https://github.com/nextdoc/sketch/blob/main/examples/mobile_weather_app/weather-model.edn)
See io.nextdoc.sketch.core/model-registry to understand the shape.

You can start a file watcher on this which will emit a Malli registry to
a [namespace of your choice](https://github.com/nextdoc/sketch/blob/main/examples/mobile_weather_app/weather_registry.cljc#L5)

clj-rewrite is used to maintain the **generated** form in this file with alphabetical order.

The watcher warns when generated registry keywords are no longer in sync with the model.

You can build out your domain directly in the generated registry or
reference [other domain registries](https://github.com/nextdoc/sketch/blob/main/examples/mobile_weather_app/weather_registry.cljc#L40)
that you merge together.

#### Interactive static HTML review tool

Each Sketch test generates its own static HTML file which is used to review the sequence of steps.

Click on the image above to experiment with the interactivity.

## Source code benefits

- Bootstrap Malli schema
    - manually populate schemas maintaining naming standard
- Build pure domain functions at design time
    - Any domain logic that affects network design can be built at design time
    - for production actors written in Clojure, these functions are reusable
    - For non-Clojure actors, A.I. can port the functions to most languages quickly
- generate non-clojure code
    - Typescript/Zod TODO
    - Apex TODO
    - Malli
        - JSON Schema
        - Open API

## Testing: tools you can use 

- a lightweight API for simulating actors, state and data flow
- composable steps tell the story of data flow
    - add network events as stories grow
- open or closed schemas
  - for state
  - for data flow
- composable chapters (step sequences)
- state and data flow automatically checked using Malli schemas
- state data flow for non-clojure systems local idioms e.g. fixtures with Swagger camelCase data
- assertions can be made about state anywhere e.g. check race conditions
  - before and after hooks available in each step
- custom Timbre Logger makes test output easier to scan
- test state stores are abstracted
    - the default uses an atom for persistence.
    - can be replaced using any database that works in the runtime
- create fns using domain data
    - add (optional) fn schemas to avoid data bugs
    - informs implementations later during build
- verify decoders
    - add recorded fixtures from your APIs
    - use decoders in the steps to translate them back into idiomatic data
- run on jvm or node using cljc
- exceptions
    - Cursive Seeker link to failing step

## Benefits

- design system before implementation
- reasoning about total system easier with diagrams (state and sequence)
- enforced naming standards means easier to remember
    - data flow events and names
    - schema names
- confidently use idiomatic (kebab-case) keywords in all domain functions
    - less mistakes forgetting which key style is used where
- Malli schemas for state and data flow ready for implementations
- full inventory of network communications
- shift left on design bugs
- communicate with diagrams before initial build
- avoid schema naming standard drift
- improves handoff between teams.
- generate non-clojure code if required

## Status

- Early alpha: likely to change but we'll strive to avoid breaking changes to the test api
- Used effectively in multiple projects at Nextdoc and other companies
- Diagram app is a proof of concept, has some rendering/UX issues on iOS
- Very few tests
- Sparse documentation
- Limited time to work on it until [Nextdoc](https://nextdoc.io/) has more bandwidth

## Roadmap

- Onboarding
    - bb setup task: creates a minimal set of files that runs a test
    - bb create test task: create a new test based on existing config and possibly an existing test
- Improved documentation
    - for developers
    - examples
        - [HTMX DOM state and javascript local dataflow](https://github.com/nextdoc/sketch/issues/17)
        - [Alternative State Store: Datomic](https://github.com/nextdoc/sketch/issues/18)
- AI integration
    - targets
        - [MCP](https://github.com/nextdoc/sketch/issues/12)
        - [Aider](https://github.com/nextdoc/sketch/issues/13)
- Watcher
    - [better formatting for schema updates](https://github.com/nextdoc/sketch/issues/3)
    - configurable keyword naming strategy
    - [generated decoders](https://github.com/nextdoc/sketch/issues/16)
- Tests: need more
- Diagram tool features
    - [partial test success render](https://github.com/nextdoc/sketch/issues/10)
    - [foreign key state rendering](https://github.com/nextdoc/sketch/issues/11)
    - [improve svg/html container, scaling](https://github.com/nextdoc/sketch/issues/14)
    - [improve diff highlights](https://github.com/nextdoc/sketch/issues/7)
    - [improve following steps state control](https://github.com/nextdoc/sketch/issues/9)

## How can you help?

- Answer questions in Slack
- Look at the [issue list](https://github.com/nextdoc/sketch/issues) and contribute a fix
    - please start with a comment in the issue to confirm your interest and plan
- If you find Sketch valuable, we'd very much
  appreciate [a sponsorship contribution](https://github.com/sponsors/nextdoc)
- Use the tool in your projects:
    - show the diagrams to your team members
    - mention Sketch was created by [Nextdoc](https://nextdoc.io/)
- Share your feedback on the socials
