import { colorFromString, initials } from '../utils';

interface AvatarProps {
  name: string;
  size?: number;
  online?: boolean;
}

export default function Avatar({ name, size = 40, online }: AvatarProps) {
  const bg = colorFromString(name || '?');
  const initial = initials(name);
  const fontSize = Math.round(size * 0.4);

  return (
    <div className="relative inline-block shrink-0">
      <div
        className="flex items-center justify-center rounded-full font-semibold text-white shadow-sm"
        style={{
          width: size,
          height: size,
          backgroundColor: bg,
          fontSize,
        }}
      >
        {initial}
      </div>
      {online !== undefined && (
        <span
          className={`absolute bottom-0 right-0 block rounded-full border-2 border-white ${
            online ? 'bg-green-500' : 'bg-gray-400'
          }`}
          style={{ width: size * 0.28, height: size * 0.28 }}
        />
      )}
    </div>
  );
}
