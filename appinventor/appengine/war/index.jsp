<%@ page import="com.google.appinventor.client.Ode" %>
<%
  String lang = "en";
  String mode = Ode.getUserNewLayout() ? "neo" : "classic";
  String style = "/" + mode + "/" + mode + ".css";
  String darkMode = Ode.getUserDarkThemeEnabled() ? "dark" : "light";
%>
<!DOCTYPE html>
<html lang="<%= lang %>" class="<%= darkMode %>">
<head>
  <meta http-equiv="Content-Type" content="text/html; charset=UTF-8">
  <meta name="gwt:property" content="locale=<%= lang %>">
  <meta name="viewport" content="width=device-width, initial-scale=1.0">
  <title>App Inventor</title>
  
  <!-- Load Tailwind CSS -->
  <link rel="stylesheet" href="/neo/css/tailwind.min.css">
  
  <!-- Load main style sheets -->
  <link rel="stylesheet" href="<%= style %>">
  
  <!-- Load icon library -->
  <link rel="stylesheet" href="https://cdnjs.cloudflare.com/ajax/libs/font-awesome/6.4.0/css/all.min.css">
  
  <!-- Load Favicon -->
  <link rel="icon" href="/favicon.ico">
  
  <!-- Dark mode detection script -->
  <script>
    // Set initial theme based on system preference or user setting
    const storedTheme = localStorage.getItem('theme') || '<%= darkMode %>';
    const systemDark = window.matchMedia('(prefers-color-scheme: dark)').matches;
    
    if (storedTheme === 'system') {
      document.documentElement.classList.toggle('dark', systemDark);
    } else {
      document.documentElement.classList.toggle('dark', storedTheme === 'dark');
    }
    
    // Store theme preference
    function setTheme(theme) {
      localStorage.setItem('theme', theme);
      if (theme === 'system') {
        document.documentElement.classList.toggle('dark', window.matchMedia('(prefers-color-scheme: dark)').matches);
      } else {
        document.documentElement.classList.toggle('dark', theme === 'dark');
      }
    }
  </script>
</head>
<body>
  <!-- Loading indicator -->
  <div id="loading">
    <div class="tw-flex tw-items-center tw-justify-center tw-h-screen">
      <div class="tw-text-center">
        <div class="tw-inline-block tw-animate-spin tw-rounded-full tw-h-16 tw-w-16 tw-border-t-2 tw-border-b-2 tw-border-blue-500"></div>
        <p class="tw-mt-4 tw-text-lg">Loading App Inventor...</p>
      </div>
    </div>
  </div>
  
  <!-- GWT script -->
  <script type="text/javascript" src="ode/ode.nocache.js"></script>
  
  <!-- Google Analytics -->
  <script>
    (function(i,s,o,g,r,a,m){i['GoogleAnalyticsObject']=r;i[r]=i[r]||function(){
    (i[r].q=i[r].q||[]).push(arguments)},i[r].l=1*new Date();a=s.createElement(o),
    m=s.getElementsByTagName(o)[0];a.async=1;a.src=g;m.parentNode.insertBefore(a,m)
    })(window,document,'script','//www.google-analytics.com/analytics.js','ga');
    
    ga('create', 'UA-5856106-2', 'auto');
    ga('send', 'pageview');
  </script>
</body>
</html>
