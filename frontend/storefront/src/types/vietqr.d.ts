declare module "vietqr" {
  export interface VietQrGenerateOptions {
    bank: string;
    accountName: string;
    accountNumber: string;
    amount: string;
    memo: string;
    media?: "png" | "jpg" | "svg";
  }

  export interface VietQrGenerateResult {
    data: {
      code: string;
      desc: string;
      data?: {
        qrCode: string;
        qrDataURL: string | null;
      };
    };
  }

  export class VietQR {
    genQRCodeBase64(options: VietQrGenerateOptions): Promise<VietQrGenerateResult>;
  }
}
