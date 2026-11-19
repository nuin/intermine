"""Check runtime class providers in an assembled InterMine WAR."""

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
    "com/sun/xml/bind/v2/ContextFactory.class",
    "org/stringtree/json/JSONWriter.class",
)
CONTAINER_CLASSES = {"javax/servlet/Servlet.class", "javax/servlet/jsp/JspPage.class"}


def verify(war_path):
    providers = {name: [] for name in REQUIRED_CLASSES}
    container_providers = []
    with zipfile.ZipFile(war_path) as war:
        for name in war.namelist():
            if not name.startswith("WEB-INF/lib/") or not name.endswith(".jar"):
                continue
            with zipfile.ZipFile(io.BytesIO(war.read(name))) as library:
                entries = set(library.namelist())
            for class_name in providers.keys() & entries:
                providers[class_name].append(name)
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
    print(f"WAR runtime checks passed: {len(providers)} unique class providers")


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("war")
    verify(parser.parse_args().war)
