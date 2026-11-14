import Image from "next/image";
import type { ArticleContentBlock, ArticleTagItem } from "@/modules/content/types";
import { cn } from "@/lib/utils";

interface ArticleContentProps {
  title: string;
  date: string;
  blocks: ArticleContentBlock[];
  tags: ArticleTagItem[];
}

export function ArticleContent({ title, date, blocks, tags }: ArticleContentProps) {
  return (
    <article className="w-full">
      <header>
        <h1 className="mb-3 text-left text-[26px] leading-[34px] font-bold uppercase text-ivy-dark">
          {title}
        </h1>
        <div className="mb-6 text-[13px] text-[#A8A9AD]">{date}</div>
      </header>

      <div className="mx-auto w-full max-w-full">
        {blocks.map((block, index) => {
          if (block.type === "image" && block.src) {
            return (
              <Image
                key={index}
                src={block.src}
                alt={title}
                width={800}
                height={600}
                sizes="(min-width: 1024px) 700px, 100vw"
                className="my-4 block h-auto w-full rounded"
              />
            );
          }

          const isCaption =
            index > 0 &&
            blocks[index - 1]?.type === "image" &&
            (block.text?.length ?? 0) < 120;

          return (
            <p
              key={index}
              className={cn(
                isCaption
                  ? "mb-4 text-center text-sm italic text-ivy-text-muted"
                  : "mb-4 text-[15px] leading-[26px] text-ivy-text",
              )}
            >
              {block.text}
            </p>
          );
        })}
      </div>

      <div className="mt-6 flex flex-col gap-4 border-t border-ivy-hairline pt-6 md:flex-row md:justify-between">
        <div className="flex flex-wrap gap-2">
          {tags.map((tag) => (
            <a
              key={tag.href}
              href={tag.href}
              className="inline-block rounded-full border border-ivy-hairline px-3.5 py-1.5 text-xs text-ivy-text transition-colors hover:border-ivy-dark hover:text-ivy-dark"
            >
              {tag.label}
            </a>
          ))}
        </div>

      </div>
    </article>
  );
}
