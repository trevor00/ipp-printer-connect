# libjpeg-turbo

- 버전: 3.2.0
- 공식 릴리스: https://github.com/libjpeg-turbo/libjpeg-turbo/releases/tag/3.2.0
- 공식 소스: https://github.com/libjpeg-turbo/libjpeg-turbo/releases/download/3.2.0/libjpeg-turbo-3.2.0.tar.gz
- 태그 커밋: c85e6b905bf237038faa936dab160ebfc5da0344
- 다운로드한 소스 아카이브 SHA-256: `6f30092cef9fb839779646608f4ee14ae3cbac989c47fa05e841b0841f09878e`

공식 소스 아카이브를 수정하지 않고 포함했다. `LICENSE.md`, `README.ijg` 등 원래의 저작권·라이선스 문서를 그대로 유지한다. 앱에는 libjpeg API를 정적으로 연결하며, private Android JPEG 라이브러리를 사용하지 않는다. 빌드 시 외부 다운로드는 하지 않는다.

Upstream 안내에 따라 별도 CMake 프로젝트로 빌드한다. TurboJPEG API, 도구, 테스트 프로그램, 산술 부호화, SIMD는 빌드하지 않는다. SIMD 비활성화는 ABI마다 별도 assembler를 요구하지 않게 하기 위한 것이며, 이미지 크기나 품질을 변경하지 않는다.

인코딩은 baseline sequential, 8-bit RGB → YCbCr 4:2:0, 선택한 JPEG 품질과 JFIF DPI를 사용한다. JPEG 품질·샘플링의 기준은 Skia의 기본 JPEG 설정과 같지만, 메모리를 전체 프레임에 비례하게 사용하는 Huffman 최적화는 하지 않는다. 이 차이는 압축된 파일의 크기·바이트열에 영향을 주며, 선택한 양자화 품질과 해상도를 낮추지 않는다. Progressive와 전체 이미지 계수 버퍼를 사용하는 옵션은 사용하지 않는다.

Skia 설정 참고:

- https://github.com/google/skia/blob/main/include/encode/SkJpegEncoder.h
- https://github.com/google/skia/blob/main/src/encode/SkJpegEncoderImpl.cpp

This software is based in part on the work of the Independent JPEG Group.
