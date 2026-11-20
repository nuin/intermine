"""Check required classes and conflicting class definitions in an InterMine WAR."""

import argparse
import io
import zipfile

REQUIRED_CLASSES = (
    "antlr/Token.class",
    "org/apache/tools/ant/Project.class",
    "org/apache/commons/beanutils/BeanUtils.class",
    "org/apache/commons/collections/CollectionUtils.class",
    "org/apache/commons/codec/binary/Base64.class",
    "org/apache/commons/digester/Digester.class",
    "org/apache/commons/fileupload/FileUpload.class",
    "org/apache/commons/io/IOUtils.class",
    "org/apache/commons/validator/Validator.class",
    "org/postgresql/Driver.class",
    "org/json/JSONObject.class",
    "javax/xml/bind/JAXBContext.class",
    "javax/activation/DataHandler.class",
    "javax/inject/Inject.class",
    "org/apache/commons/logging/LogFactory.class",
    "org/apache/oltu/oauth2/client/OAuthClient.class",
    "org/apache/oltu/oauth2/common/OAuth.class",
    "com/sun/xml/bind/v2/ContextFactory.class",
    "org/stringtree/json/JSONWriter.class",
    "org/apache/solr/client/solrj/impl/HttpJdkSolrClient.class",
    "org/apache/lucene/store/ByteBuffersDirectory.class",
    "org/slf4j/reload4j/Reload4jServiceProvider.class",
)
CONTAINER_CLASSES = {"javax/servlet/Servlet.class", "javax/servlet/jsp/JspPage.class"}


def verify(war_path):
    providers = {name: [] for name in REQUIRED_CLASSES}
    container_providers = []
    with zipfile.ZipFile(war_path) as war:
        for name in war.namelist():
            if name.startswith("WEB-INF/classes/") and name.endswith(".class"):
                entries = {name[len("WEB-INF/classes/") :]}
            elif name.startswith("WEB-INF/lib/") and name.endswith(".jar"):
                with zipfile.ZipFile(io.BytesIO(war.read(name))) as library:
                    entries = set(library.namelist())
            else:
                continue
            for class_name in sorted(entries):
                if (
                    class_name.endswith(".class")
                    and not class_name.startswith("META-INF/")
                    and not class_name.endswith("module-info.class")
                ):
                    providers.setdefault(class_name, []).append(name)
            if CONTAINER_CLASSES & entries:
                container_providers.append(name)
    errors = [
        f"{name}: expected one provider, found {jars}"
        for name, jars in providers.items()
        if len(jars) != 1
    ]
    if container_providers:
        errors.append(f"Servlet/JSP APIs must come from Tomcat: {container_providers}")
    if errors:
        raise SystemExit("\n".join(errors))
    print(
        f"WAR runtime checks passed: {len(REQUIRED_CLASSES)} required providers, "
        f"{len(providers)} unique runtime classes"
    )


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("war")
    verify(parser.parse_args().war)
