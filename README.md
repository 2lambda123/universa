# Universa

> __Important notice__. Domain `icodici.com` has recently been squatted with the help of a godaddy.com registrar that just cancelled the account (maybe because it was long time ago created by a Russian). They were even so kind to propose us to buy it back in their auction. __As Universa's main idea is to fight any type of fraud and unequity we reject it and will just move out of their reach all our companies and domains__. This will soon be reflected in the codebase.
 
---------------

This is the Universa network codebase (Java core), containing the node, the console client (`uniclient`) and the associated subsystems.

The latest documentation on Universa is available in Universa Knowledge Base at [kb.universablockchain.com](https://kb.universablockchain.com). For a visual guide on the documentation topics, visit the Universa Development Map at [lnd.im/UniversaDevelopmentMap](https://lnd.im/UniversaDevelopmentMap).


## Docker image

If you are looking for the information on Universa Docker images, please consult the [kb.universablockchain.com/universa_docker_images/92](https://kb.universablockchain.com/universa_docker_images/92) Knowledge Base page.


## Build Dependencies

To build, you need to have the following installed:

- [JDK 8](http://www.oracle.com/technetwork/java/javase/downloads/jdk8-downloads-2133151.html) or newer (being tested with OpenJDK 11).
- [Gradle](https://gradle.org/install).

To access the Github repository, you may need some Git client. The further examples assume you are using the command-line `git` client. To properly clone it from Github, you may need to create an SSH key pair, to register on [Github site](https://github.com) and to [add your SSH public key](https://github.com/settings/keys) on Github.

### Build Dependencies: Debian/Ubuntu Linux

(Tested with Ubuntu 16.04 LTS, Debian Linux 9.3).

To build Universa, you need to setup Java (JDK) and Gradle (the Java build tool used in Universa).

Note: alternately, you may set up OpenJDK 11 using the standard packages for your Linux distribution. This is not covered here as usually it is much easier.

#### Setup Java: webupd8 approach (suggested)

    sudo -s
    echo "deb http://ppa.launchpad.net/webupd8team/java/ubuntu xenial main" | tee /etc/apt/sources.list.d/webupd8team-java.list
    echo "deb-src http://ppa.launchpad.net/webupd8team/java/ubuntu xenial main" | tee -a /etc/apt/sources.list.d/webupd8team-java.list
    apt-key adv --keyserver hkp://keyserver.ubuntu.com:80 --recv-keys EEA14886
    apt-get update
    apt-get install oracle-java8-installer oracle-java8-set-default binfmt-support
    exit

#### Setup Java: java-package approach (alternative)

First, install `java-package` (the tool that will help you to pack the Oracle JDK tarball into the .deb package).

    sudo apt-get install -y java-package

Visit the [Oracle JDK 8 site](http://www.oracle.com/technetwork/java/javase/downloads/jdk8-downloads-2133151.html) and download the tarball (`.tar.gz`) package for Linux x64 (assuming you run it on one). You will get a file like `jdk-8u152-linux-x64.tar.gz` (the title may vary, depending on the version).

Using the `java-package` framework, convert the tarball into the .deb package (replace the `jdk-8u152-linux-x64.tar.gz` with your specific name, if needed).

    make-jpkg jdk-8u152-linux-x64.tar.gz

It will create a .deb package named accordingly. Install it (replace the `oracle-java8-jdk_8u152_amd64.deb` with your specific name, if needed).

    sudo dpkg -i oracle-java8-jdk_8u152_amd64.deb

You may also need to configure your future Gradle environment to refer to the just-installed JDK:

    mkdir -p ~/.gradle
    cat <<EOT > ~/.gradle/gradle.properties
    org.gradle.java.home=/usr/lib/jvm/oracle-java8-jdk-amd64
    EOT


#### Setup Gradle

    sudo apt-get install -y gradle

## How to build Universa

Create a directory where the project will be placed, and, inside it, run the following commands:

    git clone git://github.com/UniversaBlockchain/universa.git ./

Then you can build it.

Universa node may be built either in multi-jar configuration (suggested),..

     gradle :universa_node:buildMultiJar -x test

... or as a “fat jar”:

    gradle :universa_node:fatJar

Uniclient (CLI interface to Universa) is built as “fat jar” only:

    gradle :uniclient:fatJar

To successfully compile it, you may need [GNU Multiple Precision Arithmetic Library](http://gmplib.org/) (“`libgmp`”) installed. See the details specific to your operating system regarding how it can be installed.

## Launching

After building launch Universa components.

Launching the node, built in “multi-jar” configuration:

    java -jar universa_node/build/output/uninode.jar

Launching the node, built in “fat jar” configuration:

    java -jar universa_node/build/libs/uninode-all.jar

Launching uniclient:

    java -jar uniclient/build/libs/uniclient.jar

### Windows-specific

To build Universa under Windows, you need to install the Oracle JDK and Gradle from the binary distributions, and use them similarly. You will need to pass an extra `-Dfile.encoding=utf-8` option to `gradle`; so the build commands

    gradle :universa_node:jar
    gradle :uniclient:fatJar

become

    gradle -Dfile.encoding=utf-8 :universa_node:fatJar
    gradle -Dfile.encoding=utf-8 :uniclient:fatJar
