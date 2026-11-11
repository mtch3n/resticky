Import("env")

from os import replace
from os.path import join


def sign(source, target, env):
    image = str(target[0])
    key = join(env.subst("$PROJECT_DIR"), "signing", "firmware-signing.pem")
    espsecure = join(env.PioPlatform().get_package_dir("tool-esptoolpy"), "espsecure.py")
    if env.Execute(f'"$PYTHONEXE" "{espsecure}" sign_data --version 2 --keyfile "{key}" --output "{image}.signed" "{image}"'):
        env.Exit(1)
    replace(image + ".signed", image)


env.AddPostAction("$BUILD_DIR/${PROGNAME}.bin", sign)
