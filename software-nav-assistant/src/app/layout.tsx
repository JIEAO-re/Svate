import type { Metadata } from "next";
import "./globals.css";

export const metadata: Metadata = {
  title: "Svate · 亲情导航向导",
  description:
    "Svate 长辈手机操作导航助手：通过屏幕共享实时识别手机界面，用大白话一步步引导长辈完成操作，并对支付、转账等高危操作自动熔断。",
};

export default function RootLayout({
  children,
}: Readonly<{
  children: React.ReactNode;
}>) {
  return (
    <html lang="zh-CN">
      <body className="antialiased">
        {children}
      </body>
    </html>
  );
}
