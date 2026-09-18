import type { Metadata } from "next";
import "./globals.css";

export const metadata: Metadata = {
  title: "АРМ ДДС — учебный тренажёр 112",
  description: "Интерфейс обучающегося для работы с карточками происшествий системы 112",
};

export default function RootLayout({ children }: Readonly<{ children: React.ReactNode }>) {
  return (
    <html lang="ru">
      <body>{children}</body>
    </html>
  );
}
