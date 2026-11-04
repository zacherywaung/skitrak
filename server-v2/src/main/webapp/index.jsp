<%@ page contentType="text/html;charset=UTF-8" language="java" %>
<html>
<head>
    <title>SkiTrak</title>
</head>
<body>
<h1>Ski Resort API</h1>
<p>SkiTrak is running!</p>
<p>Current time: <%= new java.util.Date() %></p>

<h2>API Endpoints:</h2>
<ul>
    <li>GET /skiers/{resortID}/seasons/{seasonID}/days/{dayID}/skiers/{skierID}</li>
    <li>POST /skiers/{resortID}/seasons/{seasonID}/days/{dayID}/skiers/{skierID}</li>
</ul>
</body>
</html>