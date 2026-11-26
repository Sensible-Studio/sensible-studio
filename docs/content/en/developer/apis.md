---
title: APIs
description: Read and write Sensible Studio content from an application: JSON over HTTP, GraphQL for content fragments, the page model, and OAuth 2.0 tokens.
order: 50
---

Everything in Sensible Studio is a node in the repository, and every node has an address. An application reads and writes content over HTTP, as a user, with that user's rights.

## Signing in

| How | For what |
|---|---|
| **OAuth 2.0 token** | Applications. `Authorization: Bearer sst_…`. See below. |
| **Basic authentication** | Scripts and tools on a trusted network. |
| **The sign-in cookie** | The browser. |

Every request that changes something must carry the header `X-Studio-CSRF: 1`, however it signed in. A browser cannot be made to send that header from another site, which is the point of it.

## Read content

Any node answers as JSON: add `.json`, and a number for the depth.

```
GET /content/weekender/us/en/magazine.json            the node's own properties
GET /content/weekender/us/en/magazine.2.json          two levels below
GET /content/weekender/us/en/magazine.infinity.json   everything below
```

A page also answers with its **model**: the components of the page as the front end needs them, each exported by its Sling Model. This is what a single-page application renders from.

```
GET /content/weekender/us/en/magazine.model.json
```

On the live site only what the Live cache's filter allows is reachable: pages, `.model.json`, persisted GraphQL queries, media. The plain `.json` views are an author-side tool.

## Write content

Content is written with the Sling POST servlet: a form POST to the node's address.

```
curl -u user:password -H "X-Studio-CSRF: 1" -F "jcr:title=New title" \
     http://localhost:4502/content/mysite/en/page/jcr:content
```

| Field | What it does |
|---|---|
| `name=value` | Sets a property. `name@TypeHint=Date`, `String[]`, `Boolean` set the type. |
| `name@Delete` | Removes a property. |
| `:operation=delete` | Removes the node. |
| `:operation=move`, `:operation=copy` with `:dest` | Moves or copies it. |
| `:operation=import` with `:contentType=json`, `:content` | Creates a tree from JSON. This documentation site is built with it; see `docs/build.py`. |
| `:order=before <name>`, `last`, `first` | Orders it among its siblings. |

Writes go through Oak's access control with the user's own session. There is no service account behind the API that could do more than the caller.

## GraphQL for content fragments

Content fragments are queried with GraphQL. The schema is made from the content fragment models: a model *Article* gives the queries `articleList`, `articleByPath` and `articlePaginated`.

| | |
|---|---|
| Global endpoint | `/content/graphql/global/endpoint.json` |
| Endpoint of a configuration | `/content/cq:graphql/<configuration>/endpoint.json` |
| Persisted query | `GET /graphql/execute.json/<configuration>/<query>` |
| With variables | `GET /graphql/execute.json/<configuration>/<query>;name=value` |

```
curl -u user:password -H "Content-Type: application/json" \
     -d '{"query":"{ articleList { items { _path title } } }"}' \
     http://localhost:4502/content/graphql/global/endpoint.json
```

**Home › System › Developer › GraphQL** lists the endpoints and their persisted queries. **GraphQL Query Editor** runs a query against an endpoint, shows the schema, and saves a query as a persisted query with its GET address.

Use **persisted queries** on the live site. They are plain GETs, so the Live cache keeps their answers and removes them when a fragment they read is published. A POST to an endpoint is never cached, and the shipped filter does not let it through.

## OAuth 2.0

An application acts as a user with a token instead of a password. Register it in **Home › Users › OAuth**: a name, its redirect addresses, and what it may do.

| | |
|---|---|
| Authorize | `GET /oauth/authorize` (the consent page, for a signed-in user) |
| Token | `POST /oauth/token` |
| Revoke | `POST /oauth/revoke` |
| Scopes | `read`, `write`, `offline_access` |

**Grants**

- **Authorization code with PKCE** (S256, required), for an application acting for a person. Redirect addresses must match exactly.
- **Refresh token**, with rotation: each use gives a new one. Using an old one again ends the whole grant.
- **Client credentials**, for a service with no person present. The client runs as a user you name, never as `admin`.

**Tokens**

- Access tokens (`sst_…`) last an hour; refresh tokens (`ssr_…`) thirty days; codes ten minutes.
- Only hashes are stored. A client's secret is shown once, when you create it.
- A token with `read` only may GET. `write` is needed for anything else.
- A token cannot be used to manage OAuth clients and tokens themselves.

```
curl -H "Authorization: Bearer sst_…" \
     http://localhost:4502/content/weekender/us/en.model.json
```

## The product's own endpoints

The authoring interface talks to servlets under `/bin/studio/`. They are stable enough to script against, and they check the caller's rights like everything else, but they are the interface's own and may change between versions. The list is in the [reference](reference.md). Prefer the content APIs above where they do the job.

Publishing is the one you are most likely to need:

```
curl -u user:password -H "X-Studio-CSRF: 1" \
     -d "op=activate" -d "path=/content/mysite/en/page" \
     http://localhost:4502/bin/studio/replication.json
```

`op=deactivate` unpublishes; `deep=true` includes the pages below; `at=<ISO date>` schedules it.

## Media by address

An image is delivered at any size by its address, on the publish instance and through the Live cache. See [Images and delivery](../author/media-delivery.md) for the parameters and the presets.
