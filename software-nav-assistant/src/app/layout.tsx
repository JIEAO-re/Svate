import type { Metadata } from "next";
import "./globals.css";

export const metadata: Metadata = {
  title: "Svate · 通用手机 Agent",
  description:
    "Svate 通用手机 Agent：理解你的目标，实时看懂手机屏幕，一步步引导你在任意 App 中完成操作，并对支付、转账等高危动作自动熔断。",
};

export default function RootLayout({
  children,
}: Readonly<{
  children: React.ReactNode;
}>) {
  return (
    <html lang="zh-CN">
      <body className="antialiased text-gray-900">
        {children}
      </body>
    </html>
  );
}
