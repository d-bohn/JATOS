name := "MechArg"

version := "1.0-SNAPSHOT"

libraryDependencies ++= Seq(
  javaCore,
  javaJdbc,
  javaJpa,
  "org.hibernate" % "hibernate-entitymanager" % "4.2.15.Final",
  "mysql" % "mysql-connector-java" % "5.1.18",
  "com.google.inject" % "guice" % "3.0"
)

playAssetsDirectories <+= baseDirectory / "studies"

play.Project.playJavaSettings

mappings in Universal += file(baseDirectory.value + "/start.sh") -> ("start.sh")
