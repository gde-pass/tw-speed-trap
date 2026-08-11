# Release signing

The release APK is signed in CI with a keystore stored **only** in GitHub
Secrets. The keystore must stay the same forever: Android identifies an app
by (applicationId, signing key), so a new key would force friends to
uninstall/reinstall instead of updating in place.

## One-time setup

1. Generate the keystore (**outside** git history; `*.jks` is gitignored,
   but keep it out of the repo directory entirely if you prefer):

   ```sh
   keytool -genkeypair -v \
     -keystore twspeedtrap-release.jks \
     -alias twspeedtrap \
     -keyalg RSA -keysize 4096 \
     -validity 10950 \
     -dname "CN=TW SpeedTrap"
   ```

   Pick one strong password when prompted and use it for both the store and
   the key (the modern PKCS12 format uses a single password anyway).

2. Store the four secrets in the GitHub repo:

   ```sh
   base64 -i twspeedtrap-release.jks | gh secret set KEYSTORE_B64 --repo gde-pass/tw-speed-trap
   gh secret set KEYSTORE_PASSWORD --repo gde-pass/tw-speed-trap   # paste the password
   gh secret set KEY_ALIAS --repo gde-pass/tw-speed-trap --body twspeedtrap
   gh secret set KEY_PASSWORD --repo gde-pass/tw-speed-trap        # same password
   ```

3. **Back up** `twspeedtrap-release.jks` and its password in your password
   manager. If both the GitHub secret and your local copy are lost, the app
   can never be updated in place again.

## Releasing

```sh
git tag v1.0.0 && git push origin v1.0.0
```

`release.yml` builds the signed APK and attaches it to the GitHub Release.
Obtainium picks it up automatically.

## Local signed build (optional)

```sh
export KEYSTORE_FILE=/path/to/twspeedtrap-release.jks
export KEYSTORE_PASSWORD=… KEY_ALIAS=twspeedtrap KEY_PASSWORD=…
./gradlew assembleRelease
```

Without those variables, `assembleRelease` produces an unsigned APK (fine
for CI checks, not installable on top of a signed install).
