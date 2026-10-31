#!/usr/bin/env python3
"""Build bundled QPDF and JPEG. Needs CMake, make, NDK r27c+ (Android) or C++17 (host)."""
import os, sys, subprocess, pathlib, shutil
root = pathlib.Path(__file__).resolve().parents[1]
arch = sys.argv[1] if len(sys.argv) > 1 else 'arm64-v8a'
cmake = os.environ.get('CMAKE', 'cmake')
build = root / 'native-build' / arch
build.mkdir(parents=True, exist_ok=True)
env = os.environ.copy()
empty = build / 'empty-pkgconfig'; empty.mkdir(exist_ok=True)
env['PKG_CONFIG_LIBDIR'] = str(empty)
base = ['-DCMAKE_BUILD_TYPE=Release', '-DCMAKE_POSITION_INDEPENDENT_CODE=ON', '-DCMAKE_POLICY_VERSION_MINIMUM=3.5']
if arch != 'host':
    ndk = pathlib.Path(os.environ['ANDROID_NDK_HOME'])
    base += ['-DCMAKE_TOOLCHAIN_FILE='+str(ndk/'build/cmake/android.toolchain.cmake'),
        '-DANDROID_ABI='+arch, '-DANDROID_PLATFORM=android-29', '-DANDROID_STL=c++_static',
        '-DANDROID_SUPPORT_FLEXIBLE_PAGE_SIZES=ON']
def run(args): subprocess.run([str(x) for x in args], env=env, check=True)
jbuild = build/'jpeg'; prefix = build/'jpeg-install'
run([cmake, '-S', root/'third_party/libjpeg-turbo-3.1.0', '-B', jbuild, *base,
     '-DENABLE_SHARED=OFF', '-DENABLE_STATIC=ON', '-DWITH_TURBOJPEG=OFF', '-DWITH_SIMD=OFF',
     '-DCMAKE_INSTALL_PREFIX='+str(prefix)])
run([cmake, '--build', jbuild, '--target', 'jpeg-static', '-j', '4'])
(prefix/'include').mkdir(parents=True, exist_ok=True); (prefix/'lib').mkdir(exist_ok=True)
for name in ['jpeglib.h','jmorecfg.h','jerror.h']:
    shutil.copy2(root/'third_party/libjpeg-turbo-3.1.0'/('src/'+name), prefix/'include'/name)
shutil.copy2(jbuild/'jconfig.h', prefix/'include/jconfig.h')
shutil.copy2(jbuild/'libjpeg.a', prefix/'lib/libjpeg.a')
qbuild = build/'app'
run([cmake, '-S', root/'native', '-B', qbuild, *base,
     '-DLIBJPEG_H_PATH='+str(prefix/'include'), '-DLIBJPEG_LIB_PATH='+str(prefix/'lib/libjpeg.a')])
targets = ['paperslice'] if arch != 'host' else ['paperslice-cli', 'paperslice', 'qpdf']
run([cmake, '--build', qbuild, '--target', *targets, '-j', '4'])
if arch != 'host':
    destination = root/'app/src/main/jniLibs'/arch/'libpaperslice.so'
    destination.parent.mkdir(parents=True, exist_ok=True)
    shutil.copy2(qbuild/'libpaperslice.so', destination)
    run([ndk/'toolchains/llvm/prebuilt/linux-x86_64/bin/llvm-strip', '--strip-unneeded', destination])
print('Native build ready:', arch)
