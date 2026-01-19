# Tag and Release Process

This document describes how to create a new release that updates the CDN-hosted ClojureScript assets.

## Background

When Sketch generates HTML diagram artifacts, they load the diagram app JavaScript from jsDelivr CDN:

```
https://cdn.jsdelivr.net/gh/nextdoc/sketch@r{version}/app/main.js
```

The version references a branch (e.g., `r0.1.31`) that contains only the built artifacts. This branch is created automatically by GitHub Actions when you push a tag.

## Release Steps

### 1. Update the tag version in run.clj

Edit `src/io/nextdoc/sketch/run.clj` and update the hardcoded tag:

```clojure
:tag "r0.1.32"  ;; increment version number
```

This is located in the `write-sequence-diagram!` function call within `run-steps!`.

### 2. Commit and push

```bash
git add -A
git commit -m "Bump version to 0.1.32"
git push origin main
```

### 3. Create and push the git tag

```bash
git tag 0.1.32
git push origin 0.1.32
```

**Note:** The tag does NOT have the `r` prefix. The GitHub Action adds the prefix when creating the release branch.

### 4. GitHub Actions handles the rest

When the tag is pushed, the `release.yml` workflow automatically:

1. Runs all tests (including examples)
2. Builds the production ClojureScript: `clojure -M:cljs-diagram:build release diagram`
3. Creates a branch named `r{tag}` (e.g., `r0.1.32`)
4. Adds the built `app/main.js` and `app/main.js.map` to this branch
5. Pushes the branch

### 5. Verify the release

After the GitHub Action completes, verify the new version is available:

```
https://cdn.jsdelivr.net/gh/nextdoc/sketch@r0.1.32/app/main.js
```

You can check the workflow status at: https://github.com/nextdoc/sketch/actions

## Version Numbering

Versions follow the pattern `{major}.{minor}.{patch}`:
- **major**: Breaking changes to the API or model format
- **minor**: New features, backward compatible
- **patch**: Bug fixes, backward compatible

Note: Tags use the version directly (e.g., `0.1.32`), but the CDN URL and `run.clj` reference branches with an `r` prefix (e.g., `r0.1.32`).

## Development Mode

During development, run tests with `:dev? true` to load from the local Shadow-CLJS dev server (`http://localhost:8000/diagram-js/main.js`) instead of the CDN. This allows hot-reloading of ClojureScript changes.

Start the dev server:
```bash
clj -M:cljs-diagram -m shadow.cljs.devtools.cli watch diagram
```

## Troubleshooting

### CDN not updating?

jsDelivr caches aggressively. If you need to purge the cache, visit:
```
https://purge.jsdelivr.net/gh/nextdoc/sketch@r0.1.32/app/main.js
```

### GitHub Action failed?

Check the workflow logs at https://github.com/nextdoc/sketch/actions. Common issues:
- Test failures - fix the tests and create a new tag
- Build failures - check the ClojureScript compilation output
