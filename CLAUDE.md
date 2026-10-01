# doc-viewer — Hoffice: 폰 문서뷰어 (폴라리스오피스 대용)

## 목적
안드로이드 폰에서 인터넷 없이 HWP·HWPX·DOC·DOCX·XLS·XLSX·PPT·PPTX·PDF(+ODF·RTF·CSV·TXT·그림)를 보는 앱.
카톡·메일 첨부를 누르면 바로 열리고, 찾기·쪽 이동·원본/PDF 공유·비밀번호 문서를 지원한다.
0.4.0: 폰에 저장(Download/Hoffice)·인쇄·쪽 그림 공유·읽던 자리·즐겨찾기·종류 거르기·내용으로 찾기·야간 모드·두 번 탭 확대·
시트 탭·슬라이드쇼·쪽 한눈에 보기·목차·PDF 링크·워드 메모·글자 선택/복사·ZIP 첨부·읽어 주기(TTS).

## 구조
| 형식 | 엔진 | 위치 |
|---|---|---|
| HWP/HWPX/HWP3/HML | rhwp (Rust→WASM, MIT) — **패치본** | `app/src/main/assets/web/rhwp/`, `hwp-worker.js` |
| 오피스 전부 | LibreOfficeKit (F-Droid LibreOffice Viewer 26.2.6.3 의 네이티브 라이브러리 재사용, MPL-2.0) → PDF 변환 | `OfficeService.kt` (별도 프로세스 `:office`) |
| PDF | pdf.js 6.3 (legacy build) | `app/src/main/assets/web/pdfjs/` |

- 화면은 전부 WebView(`viewer.html` / `viewer.js`). 문서 바이트는 `WebViewAssetLoader` 의 `/doc/` 로 공급.
- 형식 판별은 확장자가 아니라 내용(매직바이트)으로: `DocKind.kt`.
- 연 문서는 `files/docs/<sha1>.<ext>` 로 복사 보관(카톡 권한 만료 대비), 오피스 변환 PDF 는 `cache/pdf/` 캐시.

## 빌드
```bash
./scripts/fetch-libreoffice.sh          # 처음 한 번 (엔진 196MB 는 git 에 없음)
./gradlew assembleRelease               # app/build/outputs/apk/release/app-release.apk (~99MB)
```
JDK 17, SDK 34, NDK 27.1.12297006, CMake 3.22.1 (`libdvlok.so` 빌드용).

## 반드시 알아야 할 함정 (2026-10-01 실측)
1. **LibreOffice 는 `Batch=true` 로 연다** (`LokExtra.documentLoadWithOptions`, `src/main/cpp/dvlok.c`).
   안 그러면 "읽기 전용으로 열까요?"·매크로 경고 같은 대화상자를 폰에서 띄우지 못해 **CPU 100% 무한 대기**.
   기본 자바 바인딩에는 이 함수가 없어 C 로 직접 부른다. `--headless` 명령행 인자는 효과 없었음.
2. **Batch 는 비밀번호 요청까지 꺼 버린다** → `Encryption.kt` 가 OLE 구조를 직접 읽어 암호화 여부를 먼저 판별,
   암호화 파일만 일반 `documentLoad` + 비밀번호 콜백 경로로 보낸다.
3. **비밀번호 콜백**: 옛 .doc 은 콜백 url 로 파일 이름만 준다 → 그대로 답하면 SalAbort. 항상 우리가 연 전체 URL 로 답할 것.
   두 번째 요청(=비번 틀림)엔 null(취소)로 답해야 함. 같은 비번을 되풀이하면 수십만 번 묻다 죽는다.
4. **아주 큰 시트는 SinglePageSheets 금지** (면적 A4 40장 초과 시 일반 쪽 나눔). 한 장짜리 거대 PDF 는 메모리 초과로 죽음.
5. **rhwp 는 SVG 경로로 그린다** (`renderPageSvg`). Canvas 경로는 WMF 차트를 안 그림.
   0.4.0 부터 inline SVG(글자 선택·검색 가능 PDF 용) — 쪽끼리 id 가 겹치므로 `prefixIds` 로 쪽마다 접두어 필수.
6. **rhwp 패치**: `patches/rhwp-wmf-patinvert-mask.patch` (base `patches/rhwp-base-commit.txt`).
   WMF 차트의 XOR 마스크 관용구(PATINVERT → 도형 → PATINVERT)를 도형 채우기로 해석 — 안 하면 꺾은선 차트가 빨간 사각형으로 덮임.
   재빌드: rhwp 체크아웃에 패치 적용 → `cargo build --lib --release --target wasm32-unknown-unknown --locked`
   → `wasm-bindgen 0.2.127 --target web` → `rhwp.js`, `rhwp_bg.wasm` 를 assets 에 복사. (Rust 1.93.1 GNU 툴체인)
7. HWP → PDF 공유는 WebView 인쇄(`android.print.PdfPrint`)로 만든다. inline SVG 라 PDF 안 글자도 검색된다.
8. 디버그 빌드는 16KB 페이지 경고가 뜬다(LibreOffice .so 가 4KB 정렬). 4KB 폰(갤럭시 S24 등)은 정상, 16KB 전용 폰에선 LO 엔진이 안 뜰 수 있음.

9. **pdf.js 6 은 `convertToViewportRectangle` 이 없다** → `convertToViewportPoint` 두 번. (0.1.0 에선 이 때문에 PDF·오피스 찾기가 늘 "없음")
10. 변환 PDF 는 `*.part` 에 쓰고 성공 시 rename(깨진 캐시 방지). 뷰어는 작업 id 로 요청, 엔진이 실제 시작할 때 `RESULT_STARTED` 를 받아 제한시간을 센다. 뷰어가 닫히면 `ACTION_CANCEL`. 엔진은 60초 놀면 스스로 종료.
11. ViewerActivity 는 export 되어 있으므로 file:// 는 공용 저장소의 일반 파일만 받는다(앱 내부 파일 유출 방지). 가져오기는 1GB 상한, 임시파일은 가져오기마다 따로.
12. 쪽 크기는 A4 폭 기준(PDF 595pt, rhwp 794px)으로 맞춘다 — 작은 쪽(두 줄짜리 시트)이 화면 가득 확대되지 않게. CSV/TSV 는 LibreOffice 를 거치지 않고 앱이 직접 표로 그린다.

13. **실행 중인 변환은 취소 = 프로세스 종료**(LibreOffice 는 중간에 멈출 수 없음). 뷰어 CANCEL 이 currentJob 이면 die(), 서비스 자체도 200초 상한.
    대기열 뷰어는 3초마다 :office 생존 확인 → 죽었으면 같은 job 1회 재전송(서비스는 같은 id 무시), 10분 대기 상한.
    프로세스는 onCreate 부터 60초 무작업이면 스스로 종료(배치 모드는 pending 으로 막음).
14. 엑셀 SinglePageSheets 판정은 **모든 시트** 면적 최댓값(LOK 크기는 활성 시트만 줌).
15. 비밀번호로 연 오피스 문서는 `cache/pdf/locked-<uuid>.pdf` 에 쓰고 뷰어 닫을 때 삭제 — 공용 캐시에 복호화본을 남기지 않는다. 암호 ODF 는 manifest.xml 의 encryption-data 로 판별.
16. HWP→PDF 인쇄는 쪽 크기별 named @page(`img.style.page`)로 가로 쪽을 가로 용지에. 인쇄 중 재요청·로딩 전 요청은 막는다.
17. viewer.js 쪽 슬롯에 gen(세대) — 멀어지거나 회전으로 리셋된 쪽의 늦게 끝난 그리기는 버린다. 찾기에는 searchSeq.
18. HEIC/HEIF 는 WebView 가 못 읽어 ImageDecoder 로 JPEG 변환 후 표시. UTF-16 텍스트는 BOM 으로 판별.
19. **HWP SVG 의 그림(`<image>`)이 글자 위를 덮는다** → 길게 눌러도 선택이 안 됨. `.page svg *` 는 pointer-events:none, text/tspan 만 auto.
20. **안드로이드 ZipFile 은 UTF-8 플래그 무시하고 준 charset 으로 모든 이름을 디코딩**한다. CP949 로 열면 UTF-8 이름 ZIP 이
    예외(→ 오피스로 잘못 감), UTF-8 로 열면 CP949 이름이 U+FFFD 로 조용히 깨짐. `ZipView.open` = UTF-8 먼저, U+FFFD 있으면 CP949.
    "MS949" 별칭도 기기마다 다를 수 있어 `korean` 은 후보 목록에서 고른다.
21. HWP 쪽 글(내용 찾기 색인·읽어 주기)은 `getPageTextLayout` 런을 줄로 묶어 만든다. `getPageText` 는 **표·글상자 글을 뺀다**.
22. 색인은 `files/text/<id>.txt`, 문서 열 때마다 3초 뒤 백그라운드로 다시 만든다(382쪽 HWPX 약 10초).

## 사용 승인 (0.5.0~)
- 처음 한 번 형님 승인 → 이후 오프라인. 서버는 `C:\H-Programs\h-license` (lic.hyt.kr), 자세한 건 그 CLAUDE.md.
- `License.kt`: 기기 번호 = SHA-256("hoffice:"+ANDROID_ID) 앞 8바이트, 열쇠는 ECDSA P-256 서명을 앱 안 공개키로 확인. 7일마다(인터넷 될 때) 재확인, revoked 면 열쇠 삭제.
- `ActivationActivity.gate`: Main·Viewer 는 **닫지 않고 아래에 둔 채** 승인 화면을 결과로 띄운다. Viewer 를 먼저 닫으면 카톡 등에서 받은 1회용 파일 읽기 권한이 사라질 수 있다.
- 승인 화면은 열려 있는 동안 4초마다 상태를 묻는다(거절·중지 상태도 — 형님이 다시 허용하면 저절로 열림).
- 승인 서버 공개키를 바꾸면 기존 사용자 전원 재승인. 비밀키를 잃지 말 것.
- 폰 정리 때 `pm clear` 하면 열쇠도 지워진다 → 형님 폰은 관리 페이지에서 다시 허용하거나 재신청.

## 시험 장치
- `OfficeService` 는 `android.permission.DUMP` 보유자(= adb shell)에게만 export. `--ez batch true` 로
  `files/batch/order.txt` 목록을 폰 안에서 연속 변환, 결과는 `files/batch/result.tsv`. `OfficeService2/3` 로 3병렬.
- 2026-10-01 결과: 실제 문서 1,212개 중 1,179개 바로 열림. 나머지 = 비번 문서 9(비번 넣으면 열림), 원래 깨진 파일 16(PC LibreOffice 도 실패),
  암호 걸린 옛 PPT 5(LibreOffice 미지원), PPT95·PPT4.0 등 3(안드로이드 LO 빌드에 필터 없음). HWP 977개는 전부 통과.

## 미완 / 다음 할 일
- 아이폰판(B안: GitHub macOS 빌드 + AltStore 무료 설치) — 형님 결정 대기
- 암호 걸린 옛 PPT 복호화
- 보류(권하지 않음): PDF 주석·서명, AI 요약, 클라우드 직접 연결
