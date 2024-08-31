import scalanative.build._

ThisBuild / scalaVersion  := "3.3.3"
ThisBuild / organization  := "ca.uwaterloo.plg"
ThisBuild / tlBaseVersion := "0.2"

val isDebug = false

ThisBuild / nativeConfig ~= { c =>
  val platformOptions = c
    .withMultithreading(true)
    .withLTO(LTO.none)
    .withGC(GC.immix)
  if (isDebug)
    platformOptions
      .withMode(Mode.debug)
      .withSourceLevelDebuggingConfig(
        _.enableAll
      )                    // enable generation of debug informations
      .withOptimize(false) // disable Scala Native optimizer
      .withMode(
        scalanative.build.Mode.debug
      ) // compile using LLVM without optimizations
    // .withCompileOptions(Seq("-DSCALANATIVE_DELIMCC_DEBUG"))
  else
    platformOptions
      .withMode(Mode.releaseFull)
      .withOptimize(true)
}

lazy val modules = List(
  pollerBear,
  tests
)

lazy val root =
  tlCrossRootProject
    .enablePlugins(NoPublishPlugin)
    .aggregate(modules: _*)

lazy val pollerBear = project
  .in(file("pollerBear"))
  .enablePlugins(ScalaNativePlugin)
  .settings(
    name := "pollerBear",
    libraryDependencies ++= Seq(
    )
  )

val munitVersion = "1.0.0-RC1"

lazy val tests = project
  .in(file("tests"))
  .enablePlugins(ScalaNativePlugin, NoPublishPlugin)
  .dependsOn(pollerBear)
  .settings(
    name := "tests",
    libraryDependencies ++= Seq(
      "org.scalameta" %%% "munit" % munitVersion % Test
    )
  )
