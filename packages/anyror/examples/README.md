# Example output

Sample artefacts produced by the AnyRoR pipeline, kept in the repo so the
rendering code has something to diff against and so a reader can see what the
scrapers actually emit without running them.

All of it is generated output for **one survey number, 221/P**, fetched from
[AnyRoR](https://anyror.gujarat.gov.in), the Gujarat government's public land
record portal. Nothing here is private to this project: the same documents are
retrievable by anyone from the portal by entering the district, taluka, village
and survey number.

| Path | What it is |
|---|---|
| `AnyRoR_SurveyNo_221_P_LandRecord.pdf` | A full record as the portal serves it |
| `AnyRoR_221_P_v2.pdf` .. `v6.pdf` | Successive passes of `make-clean-pdf.mjs`, showing the print-CSS cleanup evolving |
| `diag_221P.pdf` | Diagnostic render used to debug page-break placement |
| `vf712-capture/` | Raw capture of a VF 7/12 fetch: the HTML, a screenshot, and the parsed hits, used to work out which response actually carries the PDF |

To regenerate any of it, run the scripts in the parent directory against a
survey number of your choice. These files are checked in as fixtures, not as
data the app depends on.
