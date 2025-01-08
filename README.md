# Poller Bear

Poller bear is a minimal thread-safe IO runtime for Scala native written fully in direct Scala.

It utilizes epoll([1](https://github.com/armanbilge/epollcat/blob/main/core/src/main/scala/epollcat/unsafe/epoll.scala), and [2](https://man7.org/linux/man-pages/man7/epoll.7.html)) as well as internal DSs to handle deadlines and provide thread-safety.

## Projects built on Poller Bear
- [purl](https://github.com/amsen20/purl): A [cURL](https://curl.se/) based HTTP client for Scala native.

## Using
As of now, the Poller Bear library is not published, so you need to publish it locally.
To do so, first clone this project and then publish it using the following `sbt` command:
```
sbt publishLocal
```

After that, you can use it in your project by adding the following line to your project `sbt` build file:
```scala
  libraryDependencies += "ca.uwaterloo.plg" %%% "pollerbear" % pollerBearVersion,
```

## Testing
You can run the tests using the following command:
```
sbt test
```
