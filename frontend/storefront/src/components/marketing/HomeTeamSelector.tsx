import Link from "next/link";

const TEAMS = [
  { name: "Argentina", mark: "ARG", tone: "bg-[#75aadb] text-white" },
  { name: "Brazil", mark: "BRA", tone: "bg-[#ffdf00] text-[#176b3a]" },
] as const;

export function HomeTeamSelector() {
  return (
    <section className="mb-16">
      <div className="mb-8 text-center">
        <p className="mb-2 text-[11px] font-semibold uppercase tracking-[0.28em] text-ivy-text-muted">Áo đấu chính hãng</p>
        <h2 className="inline-block border-b border-ivy-dark pb-2 text-[18px] font-light uppercase tracking-wide text-ivy-dark md:text-[28px]">Chọn đội của bạn</h2>
      </div>
      <div className="mx-auto grid max-w-md grid-cols-2 gap-6">
        {TEAMS.map((team) => (
          <Link key={team.name} href={`/tim-kiem?q=${encodeURIComponent(team.name)}`} className="group flex w-28 shrink-0 snap-start flex-col items-center gap-3 md:w-auto">
            <span className={`flex aspect-square w-20 items-center justify-center rounded-full border-4 border-white text-lg font-bold shadow-[0_4px_20px_rgba(0,0,0,0.10)] transition-transform duration-300 group-hover:-translate-y-1 ${team.tone}`}>
              {team.mark}
            </span>
            <span className="text-center text-[12px] font-medium leading-5 text-ivy-dark">{team.name}</span>
          </Link>
        ))}
      </div>
    </section>
  );
}
