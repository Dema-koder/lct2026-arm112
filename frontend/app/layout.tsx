import type { Metadata } from "next";
import "./globals.css";
import "./roles.css";

export const metadata: Metadata = {
  title: "Учебный тренажёр 112",
  description: "Учебный сервис подготовки операторов ДДС: симулятор АРМ-112, рабочие места преподавателя и администратора",
};

export default function RootLayout({ children }: Readonly<{ children: React.ReactNode }>) {
  return (
    <html lang="ru">
      <body>{children}</body>
    </html>
  );
}
