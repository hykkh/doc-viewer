# Hoffice (doc-viewer)

안드로이드용 오프라인 문서뷰어. 인터넷 없이 폰 안에서 아래 형식을 엽니다.

- 한글: HWP, HWPX (비밀번호 문서 포함)
- 워드·엑셀·파워포인트: DOC, DOCX, XLS, XLSX, PPT, PPTX (옛날 형식 포함)
- PDF, ODF, RTF, CSV, TXT, 그림

기능: 카톡·메일 첨부 바로 열기, 문서 안 글자 찾기, 쪽 이동, 원본 공유, PDF로 공유, 폰에 있는 문서 모아 보기.

## 빌드

```bash
./scripts/fetch-libreoffice.sh
./gradlew assembleRelease
```

## 사용한 오픈소스

- [rhwp](https://github.com/edwardkim/rhwp) (MIT) — HWP/HWPX 렌더링. WMF 차트 수정 패치는 `patches/`
- [LibreOffice](https://www.libreoffice.org/) (MPL-2.0) — 오피스 문서 엔진 (F-Droid LibreOffice Viewer 빌드의 네이티브 라이브러리)
- [pdf.js](https://github.com/mozilla/pdf.js) (Apache-2.0) — PDF 표시

본 제품은 한글과컴퓨터의 한글 문서 파일(.hwp) 공개 문서를 참고하여 개발하였습니다.
