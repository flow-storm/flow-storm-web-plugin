# FlowStorm web plugin

![demo](./images/plugin_demo.png)

**Requires FlowStorm >= 4.3.0**

It currently supports and is tested with :

Web servers : 

- httpkit (2.8.0)
- ring-jetty-adapter (1.13.0)

Dabatabses libs :

- next.jdbc (1.3.994)
    
# Setup

Add [![Clojars Project](https://img.shields.io/clojars/v/com.github.flow-storm/flow-storm-web-plugin.svg)](https://clojars.org/com.github.flow-storm/flow-storm-web-plugin) 
to your dependencies.

Then add to the flow storm plugin jvm option this plugin namespace, like :


```clojure
"-Dflowstorm.plugins.namespaces=flow-storm.plugins.web.all"
```

When you open the FlowStorm UI you should see a new `Web` tab like in the picture above.

The plugin needs the recordings of the internals of the web and database libraries namespaces it is going to work with so your FlowStorm instrumentation should include 
these. You can set them via jvm options or by any other means like `"-Dclojure.storm.instrumentOnlyPrefixes=next.jdbc,org.httpkit,my-web-app-ns"`

Minimum instrumentation prefixes needed for each library :

- httpkit (`org.httpkit.server`)
- ring-jetty-adapter (`ring.adapter.jetty`)
- next.jdbc (`next.jdbc.result-set`)

# Usage

Record your activity as usual, then head to the Web tab, select the flow you recorded in and click refresh.

You should see one table per thread, and each table should contain the flow of the requests handled by that thread,
with the request, followed by your functions code and any sql statements down to the response.

Double clicking on any row should take you to that point in time in the code stepper.
