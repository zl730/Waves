import sbt._
import sbtdocker.DockerPlugin.autoImport._

object DockerSettings {
  val additionalFiles = taskKey[Seq[File]]("Additional files to copy to /opt/waves")
  val exposedPorts = taskKey[Set[Int]]("Exposed ports")

  def settings: Seq[Def.Setting[_]] =
    inTask(docker)(
      Seq(
        additionalFiles := Seq.empty,
        dockerfile := {
          val yourKitArchive = "YourKit-JavaProfiler-2019.1-docker.zip"
          val bin            = "/opt/waves/start-waves.sh"

          new Dockerfile {
            from("anapsix/alpine-java:8_server-jre")

            runRaw(s"""mkdir -p /opt/waves && \\
                    |apk update && \\
                    |apk add --no-cache openssl ca-certificates && \\
                    |wget --quiet "https://search.maven.org/remotecontent?filepath=org/aspectj/aspectjweaver/1.9.1/aspectjweaver-1.9.1.jar" -O /opt/waves/aspectjweaver.jar && \\
                    |wget --quiet "https://www.yourkit.com/download/docker/$yourKitArchive" -P /tmp/ && \\
                    |unzip /tmp/$yourKitArchive -d /usr/local && \\
                    |rm -f /tmp/$yourKitArchive""".stripMargin)

            add(additionalFiles.value, "/opt/waves/")
            runShell("chmod", "+x", bin)
            entryPoint(bin)
            expose(exposedPorts.value.toSeq: _*)
          }
        },
        buildOptions := BuildOptions(removeIntermediateContainers = BuildOptions.Remove.OnSuccess)
      ))
}