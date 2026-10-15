import QRCode from 'qrcode';

// The relay already returns qr_payload as base64 JSON. The image holds that string as is,
// which is what the phone SDKs expect to base64 decode.
export async function qrDataUrl(qrPayload: string): Promise<string> {
  return QRCode.toDataURL(qrPayload, { errorCorrectionLevel: 'M', margin: 2, width: 320 });
}
