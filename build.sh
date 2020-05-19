#
set -x

rm -fr bin
mkdir -p bin

pushd src
find . -type f -not -name '*.java' -exec cp -v --parents {} ../bin ';'
popd

/opt/jdk15/bin/javac \
  -d bin -sourcepath src \
  -p $(find lib -name '*.jar' -printf :%p) \
  $(find src -name '*.java')

rm -f nashorn-14.jar

/opt/jdk15/bin/jar \
  -c -f nashorn-14.jar \
  -e nashorn.tools.Shell \
  -C bin .

