// eJamin stores Drive links JSON-escaped ("https:\/\/drive.google.com\/..."), and the site sometimes
// carries the older open?id= form. Both reduce to a file id, which is all we need to build a
// no-auth download URL (verified: uc?export=download returns the PDF bytes directly).
export function driveUrls(link) {
  if (!link) return null;
  const clean = String(link).replace(/\\\//g, '/').trim();
  const m = clean.match(/\/file\/d\/([A-Za-z0-9_-]+)/) || clean.match(/[?&]id=([A-Za-z0-9_-]+)/);
  if (!m) return null;
  const id = m[1];
  return {
    driveFileId: id,
    viewUrl: `https://drive.google.com/file/d/${id}/view`,
    downloadUrl: `https://drive.google.com/uc?export=download&id=${id}`,
  };
}
