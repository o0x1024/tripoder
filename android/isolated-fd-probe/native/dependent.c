extern int owned_fd_answer(void);
int owned_fd_dependent_answer(void){return owned_fd_answer();}
