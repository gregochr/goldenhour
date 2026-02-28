import defaultTheme from 'tailwindcss/defaultTheme';

/** @type {import('tailwindcss').Config} */
export default {
  content: ['./index.html', './src/**/*.{js,jsx}'],
  theme: {
    extend: {
      fontFamily: {
        sans: ['IBM Plex Sans', ...defaultTheme.fontFamily.sans],
      },
      colors: {
        plex: {
          gold: '#E5A00D',
          'gold-light': '#F5C518',
          'gold-dark': '#CC8A00',
          bg: '#181A1E',
          surface: '#252830',
          'surface-light': '#2F323A',
          border: '#3A3D45',
          'border-light': '#4A4D55',
          text: '#EBEBEB',
          'text-secondary': '#A0A0A0',
          'text-muted': '#6B6B6B',
        },
        sunrise: {
          50: '#fff7ed',
          100: '#ffedd5',
          500: '#f97316',
          600: '#ea580c',
          700: '#c2410c',
        },
        sunset: {
          50: '#fdf4ff',
          100: '#fae8ff',
          500: '#a855f7',
          600: '#9333ea',
          700: '#7e22ce',
        },
      },
    },
  },
  plugins: [],
};
