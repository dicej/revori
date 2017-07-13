Revori - a revision-oriented DBMS
=================================

[![Build Status](https://travis-ci.org/dicej/revori.png?branch=master)](https://travis-ci.org/dicej/revori)

Revori is a database management system designed to handle
rapidly-changing data efficiently.  In addition to traditional
relational database queries, Revori supports query subscriptions
which allow clients to retrieve the intial result of a query followed
by a stream containing live updates to that result.

The design is a hybrid of a relational database management system and
a version control system.  A database is represented as an immutable
revision from which new revisions may be derived with data added,
subtracted, or replaced.  These revisions may be compared with each
other and/or combined using a three-way merge algorithm.

For more information, see the [Wiki](https://github.com/ReadyTalk/revori/wiki)

Status
------

The code is currently in an alpha state, meaning some stuff works,
some stuff doesn't, and the API is not yet thoroughly documented and
may change without warning.  If you're brave and want to start playing
with it anyway, you can build and browse the
[API](https://jitpack.io/com/github/dicej/revori/master-bc94402ca5-1/javadoc/index.html?com/readytalk/revori/Revision.html)
and consult the test/unittests directory for simple examples of how to
use it.

Package
-------

To add Revori as a dependency to your Gradle project, add the
following to your build.gradle:

```
repositories {
    jcenter()
    maven { url "https://jitpack.io" }
}

dependencies {
    compile 'com.github.dicej:revori:master-SNAPSHOT'
}
```

See [here](https://jitpack.io/docs/#building-with-jitpack) for help
with other build systems.

Build
-----

#### Client

You'll need to have the libreadline headers and library installed to
build the client, which is a C++ command line executable.

	$ ./gradlew client:build

#### Server

	$ ./gradlew server:build


#### All

If you want to build it all, just execute:

	$ ./gradlew build


Runtime
-------

#### Server

The following starts/stops the Revori server, which listens to localhost:8017

	$ ./gradlew server:[start,stop]


#### Client

You can use the SQL front-end client to test out Revori:

	$ ./client/build/binaries/client localhost 8017


Known Issues
------------

There is a known issue with the native client build on Mac OS X platforms.
The version of readline provided by Apple is not 100% compatible with our
client implementation. To build and use the client, install the GNU version
of readline and readline headers. The easiest way to do this is probably with
[Homebrew](http://mxcl.github.com/homebrew/):

	$ brew install readline
	$ brew link readline # This overrides the Apple-provided version, so beware.
