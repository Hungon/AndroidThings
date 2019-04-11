<h1>AndroidThings</h1>
<p>I'm going to build IoT apps such as Android Assistant.</p>

<h3>MyAssistant<h3/>
<p>This app is running on IoT device powered by Android.
You need to ensure that your<a href="https://console.developers.google.com/projectselector2/apis/api/embeddedassistant.googleapis.com/overview?supportedpurview=project&project&folder&organizationId">Google Assistant API</a> in Cloud console is enabled if you want to try this app.
After that open a terminal on your development machine and type following command to configure Python virtual environment.
</p>
<code>
$ python3 -m venv env
$ source env/bin/activate
(env) $ pip install --upgrade pip setuptools wheel
(env) $ pip install --upgrade google-auth-oauthlib[tool]
</code>
<p>
After installation navigate to your top-level project library and type the below command to get your credential.
</p>
<code>
(env) $ google-oauthlib-tool --client-secrets path/to/credentials.json --credentials shared/src/main/res/raw/credentials.json --scope https://www.googleapis.com/auth/assistant-sdk-prototype --save
</code>
<p>Replace path/to/credentials.json with the path of the JSON file you downloaded.</p>
<p>This will open browser and ask you to authorize the application to make request to the assistant.</p>


