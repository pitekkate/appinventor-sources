module.exports = {
  content: [
    "../../../../../../../../war/**/*.{html,js}",
    "../**/*.java",
    "../../../../../../../../appinventor/appengine/src/com/google/appinventor/client/**/*.java"
  ],
  theme: {
    extend: {
      colors: {
        'primary': '#2c3e50',
        'secondary': '#3498db',
        'accent': '#e74c3c',
      }
    },
  },
  plugins: [],
  prefix: 'tw-',
  important: true,
}
