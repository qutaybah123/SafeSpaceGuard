#!/usr/bin/env bash
# SafeSpaceGuard Direct Build Script
set -e
echo "Building SafeSpaceGuard APK..."
python3 -c "
import os, glob, subprocess, zipfile, shutil
proj_dir = '.'
build_dir = '/tmp/safespace_build'
out_apk = 'SafeSpaceGuard-release.apk'
sdk = '/home/qutaybah001/Android/Sdk'
aapt2 = f'{sdk}/build-tools/34.0.0/aapt2'
d8 = f'{sdk}/build-tools/34.0.0/d8'
zipalign = f'{sdk}/build-tools/34.0.0/zipalign'
apksigner = f'{sdk}/build-tools/34.0.0/apksigner'
android_jar = f'{sdk}/platforms/android-37.0/android.jar'
kotlinc = '/opt/android-studio/plugins/Kotlin/kotlinc/bin/kotlinc'

shutil.rmtree(build_dir, ignore_errors=True)
os.makedirs(f'{build_dir}/res_compiled', exist_ok=True)
os.makedirs(f'{build_dir}/classes', exist_ok=True)
os.makedirs(f'{build_dir}/dex', exist_ok=True)

print('1. Compiling resources...')
subprocess.run([aapt2, 'compile', '--dir', 'app/src/main/res', '-o', f'{build_dir}/res_compiled'], check=True)

print('2. Gathering classpaths...')
classpath_jars = [android_jar]
extracted_jars = []
for aar in glob.glob('/home/qutaybah001/.gradle/caches/modules-2/files-2.1/**/*.aar', recursive=True):
    with zipfile.ZipFile(aar, 'r') as z:
        if 'classes.jar' in z.namelist():
            jar_path = z.extract('classes.jar', f'{build_dir}/extracted_jars/{os.path.basename(aar)}')
            extracted_jars.append(jar_path)
            classpath_jars.append(jar_path)

print('3. Linking resources & generating R.java...')
aapt2_flats = glob.glob(f'{build_dir}/res_compiled/*.flat')
subprocess.run([aapt2, 'link', '-o', f'{build_dir}/unaligned.apk', '-I', android_jar, '--manifest', 'app/src/main/AndroidManifest.xml', '--java', f'{build_dir}/gen'] + aapt2_flats, check=True)

print('4. Compiling Kotlin sources & R.java...')
kt_files = glob.glob('app/src/main/java/com/safespace/guard/*.kt')
java_files = [os.path.join(r, f) for r, _, fs in os.walk(f'{build_dir}/gen') for f in fs if f.endswith('.java')]
cp_str = ':'.join(classpath_jars)
subprocess.run([kotlinc, '-cp', cp_str, '-jvm-target', '17', '-d', f'{build_dir}/classes'] + kt_files + java_files, check=True)

print('5. Dexing classes...')
class_files = [os.path.join(r, f) for r, _, fs in os.walk(f'{build_dir}/classes') for f in fs if f.endswith('.class')]
subprocess.run([d8, '--output', f'{build_dir}/dex', '--lib', android_jar] + class_files + extracted_jars, check=True)

print('6. Assembling APK...')
with zipfile.ZipFile(f'{build_dir}/unaligned.apk', 'a') as apk_zip:
    for dex in glob.glob(f'{build_dir}/dex/*.dex'):
        apk_zip.write(dex, os.path.basename(dex))
    for r, _, fs in os.walk('app/src/main/assets'):
        for f in fs:
            fp = os.path.join(r, f)
            apk_zip.write(fp, f'assets/{os.path.relpath(fp, \"app/src/main/assets\")}')

print('7. Zipaligning & Signing...')
subprocess.run([zipalign, '-f', '4', f'{build_dir}/unaligned.apk', f'{build_dir}/aligned.apk'], check=True)
keystore = '/tmp/debug.keystore'
if not os.path.exists(keystore):
    subprocess.run(['keytool', '-genkey', '-v', '-keystore', keystore, '-storepass', 'android', '-alias', 'androiddebugkey', '-keypass', 'android', '-keyalg', 'RSA', '-keysize', '2048', '-validity', '10000', '-dname', 'CN=Android Debug,O=Android,C=US'], check=True)
subprocess.run([apksigner, 'sign', '--ks', keystore, '--ks-pass', 'pass:android', '--key-pass', 'pass:android', '--out', out_apk, f'{build_dir}/aligned.apk'], check=True)
print(f'SUCCESS! APK built at: {os.path.abspath(out_apk)}')
"
