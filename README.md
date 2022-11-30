
# Project Part 2

## Files:
<p>sysdig_cmd.txt: The structure of the sysdig command entered into the terminal. Prints the event number, UNIX time+ns, process ID+name, event direction, operation type, FD type+FD name (formatted like <file>/dev/input/event2). Currently ilters events that do not belong to the following types: sendto, sendmsg, write, writev, read, readv, recvfrom, recvmsg, execve, and fcntl. Additionally filters events from spammier processes such as gnome-shell and others.</p>

<p>raw_output.txt: This is a sample file of captured events, including a file download via wget.</p>

<p>pom.xml: Maven build file, including the necessary dependencies jgrapht and javatuples. </p>
